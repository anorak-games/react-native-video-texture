package expo.modules.videotexture

import android.animation.ValueAnimator
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.Keep
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.sharedobjects.SharedObject

/// Headless video player for the WebGPU consumer — Android mirror of
/// ios/VideoTexturePlayer.swift. Transport marshals to the main looper
/// (ExoPlayer requirement); calls stay FIFO through one handler.
class VideoTexturePlayer(
  appContext: AppContext,
  pixelFormat: String,
) :
  SharedObject(appContext), VideoSourceDelegate {
  companion object {
    private var nextId = 0
  }

  val frameSourceKey: String
  val providerPtr: Long
  private val videoSource: VideoSource
  private val mainHandler = Handler(Looper.getMainLooper())
  private var appliedGeneration = -1
  private var appliedUri: String? = null
  private var snapshotStatus = 0
  private var statusSeq = 0L
  private var errorSeq = 0L
  private var rateAnimator: ValueAnimator? = null
  private var released = false
  @Volatile var volume = 1.0
    private set

  init {
    // ImageReader with usage flags (the AHardwareBuffer path) needs API 29.
    require(Build.VERSION.SDK_INT >= 29) { "VideoTexture requires Android API 29+" }
    // The Android emulator cannot import decoded frames as WebGPU textures at all
    // (see VideoSource.IS_EMULATOR); fail here rather than deep inside Dawn.
    require(!VideoSource.IS_EMULATOR) {
      "VideoTexture is not supported on the Android emulator — use a physical device"
    }
    // Frames are converted to RGBA by GlVideoBridge before they reach WebGPU,
    // whatever the decoder produced, so the delivered format is always
    // 'bgra8'. 'nv12' is accepted as the legacy request (it is the JS-side
    // default) but no longer describes the buffers.
    require(pixelFormat == "nv12" || pixelFormat == "bgra8") {
      "VideoTexture on Android supports 'nv12' (legacy alias) or 'bgra8', got '$pixelFormat'"
    }
    nextId += 1
    frameSourceKey = "player$nextId"
    providerPtr = FrameSourceNative.nativeCreate("bgra8")
    val context = requireNotNull(appContext.reactContext) { "VideoTexture: no Android context" }
    videoSource = VideoSource(context, providerPtr)
    videoSource.delegate = this
    FrameSourceNative.nativeAttachCommandTarget(providerPtr, this)
  }

  val currentTimeSec: Double
    get() = videoSource.currentTime

  fun installFrameSource(runtimePtr: Long) {
    FrameSourceNative.nativeInstall(runtimePtr, providerPtr, frameSourceKey)
  }

  fun loadClip(options: LoadClipOptions) = mainHandler.post {
    if (released) return@post
    appliedUri = options.uri
    videoSource.setLoopMode(options.loopMode)
    videoSource.setShouldAutoPlay(options.autoPlay)
    // Arm before load: loadUri carries the pending clip-start into the new
    // item's start position, and the reuse path relies on the armed value too.
    if (options.generation != appliedGeneration) {
      appliedGeneration = options.generation
      videoSource.setClipGeneration(options.generation.toLong())
      videoSource.armClipEnd(options.endSec)
      videoSource.armClipStart(options.startSec)
    }
    videoSource.loadUri(options.uri)
    publishSnapshot(1)
  }

  fun setPaused(paused: Boolean) = mainHandler.post {
    if (released) return@post
    videoSource.setShouldAutoPlay(!paused)
    if (paused) {
      videoSource.pause()
    } else {
      videoSource.play()
    }
    publishSnapshot(if (paused) 3 else snapshotStatus)
  }

  fun setRate(rate: Double) = mainHandler.post {
    if (released) return@post
    rateAnimator?.cancel()
    videoSource.setRate(rate)
    publishSnapshot(snapshotStatus)
  }

  fun rampRate(rate: Double, durationMs: Double) = mainHandler.post {
    if (released) return@post
    rateAnimator?.cancel()
    val from = videoSource.configuredRate
    rateAnimator = ValueAnimator.ofFloat(from.toFloat(), rate.toFloat()).apply {
      duration = durationMs.coerceAtLeast(0.0).toLong()
      addUpdateListener {
        videoSource.setRate((it.animatedValue as Float).toDouble())
        publishSnapshot(snapshotStatus)
      }
      start()
    }
  }

  fun seek(sec: Double) = mainHandler.post {
    if (released) return@post
    videoSource.seek(sec)
  }

  @Keep
  fun dispatchLoadClipFromNative(
    uri: String,
    startSec: Double,
    endSec: Double,
    generation: Int,
    loopMode: String,
    autoPlay: Boolean,
  ) {
    loadClip(LoadClipOptions().apply {
      this.uri = uri
      this.startSec = startSec
      this.endSec = endSec
      this.generation = generation
      this.loopMode = loopMode
      this.autoPlay = autoPlay
    })
  }

  @Keep
  fun dispatchSetPausedFromNative(paused: Boolean) {
    setPaused(paused)
  }

  @Keep
  fun dispatchSetRateFromNative(rate: Double) {
    setRate(rate)
  }

  @Keep
  fun dispatchRampRateFromNative(rate: Double, durationMs: Double) {
    rampRate(rate, durationMs)
  }

  @Keep
  fun dispatchSetVolumeFromNative(volume: Double) {
    setVolume(volume)
  }

  @Keep
  fun dispatchReleaseFrameFromNative(handle: Long) {
    videoSource.releaseFrame(handle)
  }

  fun setVolume(value: Double) {
    val normalized = if (value.isFinite()) value.coerceIn(0.0, 1.0) else 1.0
    volume = normalized
    mainHandler.post {
      if (released) return@post
      videoSource.setVolume(normalized)
    }
  }

  fun onBackground() = mainHandler.post {
    if (released) return@post
    videoSource.onBackground()
  }
  fun onForeground() = mainHandler.post {
    if (released) return@post
    videoSource.onForeground()
  }

  override fun sharedObjectDidRelease() {
    // Destroy after the posted release completes so teardown stays ordered.
    mainHandler.post {
      if (released) return@post
      released = true
      rateAnimator?.cancel()
      rateAnimator = null
      FrameSourceNative.nativeDetachCommandTarget(providerPtr)
      videoSource.release()
      FrameSourceNative.nativeDestroy(providerPtr)
    }
  }

  // MARK: - VideoSourceDelegate

  override fun onStatusChange(status: String) {
    val mapped = when (status) {
      "loading" -> 1
      "playing" -> 2
      "ready" -> 3
      "error" -> 5
      else -> 0
    }
    if (mapped == 5) errorSeq += 1
    publishSnapshot(mapped)
  }

  override fun onPlayToEnd() {
    publishSnapshot(4)
  }

  private fun publishSnapshot(status: Int) {
    if (released) return
    if (status != snapshotStatus) statusSeq += 1
    snapshotStatus = status
    FrameSourceNative.nativeUpdateTransport(
      providerPtr,
      appliedUri,
      snapshotStatus,
      statusSeq,
      errorSeq,
      null,
      videoSource.durationSec,
      videoSource.actualRate,
      appliedGeneration.toLong(),
    )
  }
}
