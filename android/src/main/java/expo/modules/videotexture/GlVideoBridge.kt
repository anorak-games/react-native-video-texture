package expo.modules.videotexture

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.util.Log
import android.view.Surface

/// GPU-only decoder-to-RGBA bridge.
///
/// Decoders hand out vendor-specific YUV gralloc layouts (Exynos SBWC/SP_M,
/// Qualcomm UBWC, Tensor SPN). Importing those into Vulkan/WebGPU as external
/// formats is driver roulette: Samsung Xclipse cannot sample their chroma
/// planes correctly through that path at all (S22 renders green, S24 teal).
/// The one path every vendor certifies is GL's external texture — it is what
/// SurfaceTexture and every video app on Android use, and the driver converts
/// its own layout to RGB itself. So: decoder -> SurfaceTexture (external
/// texture) -> one fullscreen blit -> EGL window surface backed by the
/// module's RGBA ImageReader. The reader then delivers plain RGBA8
/// AHardwareBuffers, which import into WebGPU through Dawn's ordinary color
/// path on every GPU — no YCbCr conversions, no vendor formats, no platform
/// branches for the consumer. Cost: one 4K quad per frame (<1ms GPU,
/// ~45MB/frame of DRAM traffic, well under 10% of these SoCs' bandwidth) and
/// zero CPU copies.
///
/// Threading: construct anywhere; `inputSurface` is available immediately
/// (detached SurfaceTexture). All GL/EGL work happens on `glHandler`'s
/// thread, lazily on the first frame. Call `release()` from any thread.
class GlVideoBridge(
  private val width: Int,
  private val height: Int,
  private val outputSurface: Surface,
  private val glHandler: Handler,
  private val onError: (String) -> Unit,
) {
  private val surfaceTexture = SurfaceTexture(/* singleBufferMode = */ false).apply {
    setDefaultBufferSize(width, height)
  }

  /// Hand this to ExoPlayer. Frames queue into the SurfaceTexture even before
  /// the GL side has spun up.
  val inputSurface = Surface(surfaceTexture)

  private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
  private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
  private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
  private var program = 0
  private var textureId = 0
  private var positionLoc = 0
  private var texCoordLoc = 0
  private var texMatrixLoc = 0
  private val texMatrix = FloatArray(16)
  private var eglReady = false
  private var failed = false
  @Volatile private var released = false

  // Fullscreen strip, kept as fields: the Java GLES binding hands the native
  // side a pointer into these direct buffers, so they must outlive every draw.
  // The SurfaceTexture transform matrix carries the producer's crop and
  // orientation, including the vertical flip that keeps the reader's buffers
  // top-left-origin like the decoder's were.
  private val positionBuffer = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
  private val texCoordBuffer = floatBuffer(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))

  init {
    surfaceTexture.setOnFrameAvailableListener({ drawFrame() }, glHandler)
  }

  fun release() {
    released = true
    glHandler.post {
      surfaceTexture.setOnFrameAvailableListener(null)
      if (eglReady) {
        EGL14.eglMakeCurrent(
          eglDisplay,
          EGL14.EGL_NO_SURFACE,
          EGL14.EGL_NO_SURFACE,
          EGL14.EGL_NO_CONTEXT,
        )
        if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
        if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
        eglReady = false
      }
      surfaceTexture.release()
      inputSurface.release()
    }
  }

  private fun drawFrame() {
    if (released || failed) return
    if (!eglReady && !initEgl()) {
      failed = true
      onError("GlVideoBridge: EGL initialization failed")
      return
    }
    try {
      surfaceTexture.updateTexImage()
      surfaceTexture.getTransformMatrix(texMatrix)
      GLES20.glViewport(0, 0, width, height)
      GLES20.glUseProgram(program)
      GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
      GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
      GLES20.glUniformMatrix4fv(texMatrixLoc, 1, false, texMatrix, 0)
      GLES20.glEnableVertexAttribArray(positionLoc)
      GLES20.glVertexAttribPointer(positionLoc, 2, GLES20.GL_FLOAT, false, 0, positionBuffer)
      GLES20.glEnableVertexAttribArray(texCoordLoc)
      GLES20.glVertexAttribPointer(texCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
      GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
      // Blocks while every reader slot is retained downstream — the same
      // backpressure the decoder previously exerted on the reader directly.
      if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
        throw RuntimeException("eglSwapBuffers: 0x${Integer.toHexString(EGL14.eglGetError())}")
      }
    } catch (e: Exception) {
      failed = true
      Log.e(TAG, "GlVideoBridge frame failed", e)
      onError("GlVideoBridge: ${e.message}")
    }
  }

  private fun initEgl(): Boolean {
    eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    if (eglDisplay == EGL14.EGL_NO_DISPLAY) return fail("eglGetDisplay")
    val version = IntArray(2)
    if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) return fail("eglInitialize")

    val configAttribs = intArrayOf(
      EGL14.EGL_RED_SIZE, 8,
      EGL14.EGL_GREEN_SIZE, 8,
      EGL14.EGL_BLUE_SIZE, 8,
      EGL14.EGL_ALPHA_SIZE, 8,
      EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
      EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
      EGL14.EGL_NONE,
    )
    val configs = arrayOfNulls<EGLConfig>(1)
    val numConfigs = IntArray(1)
    if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) ||
      numConfigs[0] == 0
    ) {
      return fail("eglChooseConfig")
    }
    val config = requireNotNull(configs[0])

    eglContext = EGL14.eglCreateContext(
      eglDisplay,
      config,
      EGL14.EGL_NO_CONTEXT,
      intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
      0,
    )
    if (eglContext == EGL14.EGL_NO_CONTEXT) return fail("eglCreateContext")

    eglSurface = EGL14.eglCreateWindowSurface(
      eglDisplay,
      config,
      outputSurface,
      intArrayOf(EGL14.EGL_NONE),
      0,
    )
    if (eglSurface == EGL14.EGL_NO_SURFACE) return fail("eglCreateWindowSurface")
    if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
      return fail("eglMakeCurrent")
    }
    // The reader surface is not display-synced; never throttle on it.
    EGL14.eglSwapInterval(eglDisplay, 0)

    val texIds = IntArray(1)
    GLES20.glGenTextures(1, texIds, 0)
    textureId = texIds[0]
    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
    GLES20.glTexParameteri(
      GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
      GLES20.GL_TEXTURE_MIN_FILTER,
      GLES20.GL_LINEAR,
    )
    GLES20.glTexParameteri(
      GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
      GLES20.GL_TEXTURE_MAG_FILTER,
      GLES20.GL_LINEAR,
    )
    GLES20.glTexParameteri(
      GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
      GLES20.GL_TEXTURE_WRAP_S,
      GLES20.GL_CLAMP_TO_EDGE,
    )
    GLES20.glTexParameteri(
      GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
      GLES20.GL_TEXTURE_WRAP_T,
      GLES20.GL_CLAMP_TO_EDGE,
    )
    surfaceTexture.attachToGLContext(textureId)

    program = buildProgram() ?: return fail("shader compile/link")
    positionLoc = GLES20.glGetAttribLocation(program, "aPos")
    texCoordLoc = GLES20.glGetAttribLocation(program, "aTex")
    texMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")

    eglReady = true
    return true
  }

  private fun fail(stage: String): Boolean {
    Log.e(TAG, "GlVideoBridge $stage failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
    return false
  }

  private fun buildProgram(): Int? {
    val vertex = compileShader(
      GLES20.GL_VERTEX_SHADER,
      """
      attribute vec2 aPos;
      attribute vec2 aTex;
      uniform mat4 uTexMatrix;
      varying vec2 vTex;
      void main() {
        gl_Position = vec4(aPos, 0.0, 1.0);
        vTex = (uTexMatrix * vec4(aTex, 0.0, 1.0)).xy;
      }
      """,
    ) ?: return null
    val fragment = compileShader(
      GLES20.GL_FRAGMENT_SHADER,
      """
      #extension GL_OES_EGL_image_external : require
      precision mediump float;
      uniform samplerExternalOES uTex;
      varying vec2 vTex;
      void main() {
        gl_FragColor = texture2D(uTex, vTex);
      }
      """,
    ) ?: return null
    val program = GLES20.glCreateProgram()
    GLES20.glAttachShader(program, vertex)
    GLES20.glAttachShader(program, fragment)
    GLES20.glLinkProgram(program)
    val linked = IntArray(1)
    GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
    GLES20.glDeleteShader(vertex)
    GLES20.glDeleteShader(fragment)
    if (linked[0] == 0) {
      Log.e(TAG, "link: ${GLES20.glGetProgramInfoLog(program)}")
      GLES20.glDeleteProgram(program)
      return null
    }
    return program
  }

  private fun compileShader(type: Int, source: String): Int? {
    val shader = GLES20.glCreateShader(type)
    GLES20.glShaderSource(shader, source)
    GLES20.glCompileShader(shader)
    val compiled = IntArray(1)
    GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
    if (compiled[0] == 0) {
      Log.e(TAG, "compile: ${GLES20.glGetShaderInfoLog(shader)}")
      GLES20.glDeleteShader(shader)
      return null
    }
    return shader
  }

  private fun floatBuffer(values: FloatArray) =
    java.nio.ByteBuffer.allocateDirect(values.size * 4)
      .order(java.nio.ByteOrder.nativeOrder())
      .asFloatBuffer()
      .apply {
        put(values)
        position(0)
      }

  private companion object {
    const val TAG = "VideoTexture"
  }
}
