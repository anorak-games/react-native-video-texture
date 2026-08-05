package expo.modules.videotexture

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

interface VideoSourceDelegate {
  fun onStatusChange(status: String)
  fun onPlayToEnd()
}

/// Decode engine — Android mirror of ios/VideoSource.swift. ExoPlayer renders
/// video into an ImageReader surface; each frame is pushed into the C++
/// provider as an AHardwareBuffer. Push model: no polling timer.
class VideoSource(
  private val context: Context,
  private val providerPtr: Long,
) {
  var delegate: VideoSourceDelegate? = null

  private val mainHandler = Handler(Looper.getMainLooper())
  private val probeExecutor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "videotexture.probe")
  }
  private val frameDeliveryLock = Any()
  private var frameThread: HandlerThread? = null
  private var frameHandler: Handler? = null

  private var player: ExoPlayer? = null
  private var imageReader: ImageReader? = null
  private var glBridge: GlVideoBridge? = null
  private var readerWidth = 0
  private var readerHeight = 0

  private data class RetainedImage(
    val image: Image,
    val reader: ImageReader,
  )

  private data class RetiredReader(
    val reader: ImageReader,
    val thread: HandlerThread,
    val bridge: GlVideoBridge,
  )

  private val retainedImages = mutableMapOf<Long, RetainedImage>()
  private val retiredReaders = mutableMapOf<ImageReader, RetiredReader>()
  private var frameFailurePending = false

  private var currentUri: String? = null
  private var shouldAutoPlay = true
  private var loopMode = "off"
  /// The loop mode the current player was configured with. The same-URI reuse path must
  /// not keep a player whose playlist shape (single item vs looping pair) no longer
  /// matches the requested mode.
  private var installedLoopMode = "off"
  private var desiredRate = 1.0
  private var armedClipStartSec: Double? = null
  private var endedReported = false
  @Volatile private var loadGeneration = 0
  // Written by the main thread and stamped onto frames from the ImageReader thread.
  @Volatile private var clipGeneration = 0L
  @Volatile var volume = 1.0
    private set
  private var released = false

  // Extrapolation tuple so currentTime is safe off the main thread.
  @Volatile private var timeSnapshot = TimeSnapshot(0.0, 0L, 0.0, false)

  private data class TimeSnapshot(
    val positionSec: Double,
    val uptimeMs: Long,
    val rate: Double,
    val isPlaying: Boolean,
  )

  val currentTime: Double
    get() {
      val snap = timeSnapshot
      if (!snap.isPlaying) return snap.positionSec
      val elapsed = (SystemClock.uptimeMillis() - snap.uptimeMs) / 1000.0
      return snap.positionSec + elapsed * snap.rate
    }

  val durationSec: Double
    get() {
      val value = player?.duration ?: C.TIME_UNSET
      return if (value == C.TIME_UNSET) 0.0 else value / 1000.0
    }

  val actualRate: Double
    get() = if (player?.isPlaying == true) player?.playbackParameters?.speed?.toDouble() ?: 0.0 else 0.0

  val configuredRate: Double
    get() = desiredRate

  // MARK: - Transport (all called on the main thread via runOnMain)

  fun setShouldAutoPlay(autoPlay: Boolean) {
    shouldAutoPlay = autoPlay
  }

  /// Select the transport loop mode: `"loop"` or anything else = `"off"`. Takes effect on
  /// the next `loadUri` — `loadClip` always sets the mode before loading, and the reuse
  /// guard on `installedLoopMode` forces a fresh playlist when the mode changed.
  fun setLoopMode(mode: String) {
    loopMode = if (mode == "loop") "loop" else "off"
  }

  fun armClipStart(sec: Double) {
    armedClipStartSec = sec
  }

  fun setClipGeneration(generation: Long) {
    clipGeneration = generation
  }

  fun loadUri(uri: String?) {
    if (released) return
    loadGeneration += 1
    if (uri == null) {
      teardown()
      delegate?.onStatusChange("idle")
      return
    }
    if (uri == currentUri && player != null && loopMode == installedLoopMode) {
      // Reuse path: same clip stays loaded; a pending armed start is applied as a seek.
      // (Guarded on the installed loop mode: a mode change needs a fresh playlist shape.)
      endedReported = false
      armedClipStartSec?.let { start ->
        armedClipStartSec = null
        seek(start)
      }
      val p = requireNotNull(player)
      p.playWhenReady = shouldAutoPlay
      return
    }

    currentUri = uri
    endedReported = false
    delegate?.onStatusChange("loading")

    val generation = loadGeneration
    probeExecutor.execute {
      val probe = try {
        probeVideo(uri)
      } catch (_: Exception) {
        null
      }
      mainHandler.post {
        if (generation != loadGeneration || released) return@post
        if (probe == null || probe.rotationDegrees != 0) {
          failPlayback()
          return@post
        }
        try {
          ensureReader(probe.width, probe.height)
          FrameSourceNative.nativeClearLatest(providerPtr)
          val p = ensurePlayer()
          val startSec = armedClipStartSec ?: 0.0
          armedClipStartSec = null
          val item = MediaItem.fromUri(uri)
          if (loopMode == "loop") {
            // Seamless loop: TWO identical items + REPEAT_MODE_ALL, not REPEAT_MODE_ONE.
            // ExoPlayer prewarms the next playlist period, so the item transition is
            // genuinely gapless; REPEAT_MODE_ONE resets the renderer at the wrap and can hitch.
            // startSec applies to the FIRST cycle only: the loop must wrap to 0 because
            // a pre-baked loop file's seam is frame(last)→frame(0).
            p.setMediaItems(listOf(item, item), 0, (startSec * 1000).toLong())
          } else {
            p.setMediaItem(item, (startSec * 1000).toLong())
          }
          installedLoopMode = loopMode
          p.playWhenReady = shouldAutoPlay
          p.repeatMode = if (loopMode == "loop") Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
          p.playbackParameters = PlaybackParameters(desiredRate.toFloat())
          p.prepare()
        } catch (_: Exception) {
          failPlayback()
        }
      }
    }
  }

  fun play() {
    player?.play()
  }

  fun pause() {
    player?.pause()
  }

  fun setRate(rate: Double) {
    desiredRate = rate
    player?.playbackParameters = PlaybackParameters(rate.toFloat())
  }

  fun setVolume(value: Double) {
    volume = if (value.isFinite()) value.coerceIn(0.0, 1.0) else 1.0
    player?.volume = volume.toFloat()
  }

  fun seek(sec: Double) {
    val p = player ?: return
    endedReported = false
    if (loopMode == "loop") {
      // Target the CURRENT playlist item so a seek stays in this cycle instead of
      // jumping back to item 0. `duration` is per-item under a playlist.
      val durationMs = p.duration
      val clampedSec = if (durationMs != C.TIME_UNSET) {
        min(sec, durationMs / 1000.0 - 0.1)
      } else {
        sec
      }
      p.seekTo(p.currentMediaItemIndex, (max(0.0, clampedSec) * 1000).toLong())
      return
    }
    val durationMs = p.duration
    val clampedSec = if (durationMs != C.TIME_UNSET) {
      min(sec, durationMs / 1000.0 - 0.1)
    } else {
      sec
    }
    p.seekTo((max(0.0, clampedSec) * 1000).toLong())
  }

  // MARK: - Lifecycle

  fun onBackground() {
    player?.pause()
  }

  fun onForeground() {
    if (shouldAutoPlay) player?.play()
  }

  fun release() {
    if (released) return
    released = true
    loadGeneration += 1
    probeExecutor.shutdownNow()
    teardown()
    releaseAllFrames()
  }

  // MARK: - Player / reader plumbing

  private val playerListener = object : Player.Listener {
    override fun onPlaybackStateChanged(playbackState: Int) {
      when (playbackState) {
        Player.STATE_BUFFERING -> delegate?.onStatusChange("loading")
        Player.STATE_READY -> delegate?.onStatusChange("ready")
        Player.STATE_ENDED -> {
          if (loopMode == "loop") {
            // REPEAT_MODE_ALL loops before ENDED; if it ever fires, keep looping.
            player?.seekTo(0)
            player?.play()
          } else if (!endedReported) {
            endedReported = true
            delegate?.onPlayToEnd()
          }
        }
        else -> Unit
      }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
      if (isPlaying) {
        delegate?.onStatusChange("playing")
      }
    }

    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
      delegate?.onStatusChange("error")
    }
  }

  private val timeTicker = object : Runnable {
    override fun run() {
      val p = player ?: return
      // `currentPosition` is per-item, so under the loop-mode two-item playlist it is
      // already the 0→L sawtooth a loop should report.
      val positionSec = p.currentPosition / 1000.0
      timeSnapshot = TimeSnapshot(
        positionSec,
        SystemClock.uptimeMillis(),
        p.playbackParameters.speed.toDouble(),
        p.isPlaying,
      )
      mainHandler.postDelayed(this, 16)
    }
  }

  private fun ensurePlayer(): ExoPlayer {
    player?.let { return it }
    val p = ExoPlayer.Builder(context)
      .setLooper(Looper.getMainLooper())
      .build()
    p.setSeekParameters(SeekParameters.EXACT)
    // The host application owns audio focus.
    p.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).build(), false)
    p.volume = volume.toFloat()
    p.addListener(playerListener)
    requireNotNull(imageReader) { "VideoTexture: reader must exist before player" }
    val bridge = requireNotNull(glBridge) { "VideoTexture: bridge must exist before player" }
    p.setVideoSurface(bridge.inputSurface)
    player = p
    mainHandler.removeCallbacks(timeTicker)
    mainHandler.post(timeTicker)
    return p
  }

  private fun ensureReader(width: Int, height: Int) {
    if (imageReader != null && readerWidth == width && readerHeight == height) {
      return
    }
    val thread = HandlerThread("videotexture.frames").also { it.start() }
    val handler = Handler(thread.looper)
    // RGBA_8888, not the decoder's own format: frames reach this reader
    // through GlVideoBridge, which converts the decoder's vendor YUV layout to
    // plain RGBA on the GPU. RGBA8 AHardwareBuffers import into WebGPU through
    // Dawn's ordinary color path on every GPU vendor — no external formats, no
    // YCbCr conversions (see GlVideoBridge for why the direct import is not
    // viable).
    val reader = ImageReader.newInstance(
      width,
      height,
      PixelFormat.RGBA_8888,
      MAX_IMAGES,
      HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE,
    )
    val bridge = GlVideoBridge(width, height, reader.surface, handler) { message ->
      Log.e(TAG, message)
      postFrameFailure(reader)
    }

    var previousReader: ImageReader? = null
    var previousThread: HandlerThread? = null
    var previousBridge: GlVideoBridge? = null
    synchronized(frameDeliveryLock) {
      previousReader = imageReader
      previousThread = frameThread
      previousBridge = glBridge
      frameFailurePending = false
      readerWidth = width
      readerHeight = height
      imageReader = reader
      glBridge = bridge
      frameThread = thread
      frameHandler = handler
      if (previousReader != null && previousThread != null && previousBridge != null) {
        retiredReaders[requireNotNull(previousReader)] = RetiredReader(
          requireNotNull(previousReader),
          requireNotNull(previousThread),
          requireNotNull(previousBridge),
        )
      }
    }
    previousReader?.setOnImageAvailableListener(null, null)
    reader.setOnImageAvailableListener(::onImageAvailable, handler)
    try {
      player?.setVideoSurface(bridge.inputSurface)
    } finally {
      synchronized(frameDeliveryLock) {
        previousReader?.let(::closeRetiredReaderIfUnused)
      }
    }
  }

  private fun onImageAvailable(reader: ImageReader) {
    synchronized(frameDeliveryLock) {
      if (imageReader !== reader) return
      val image = try {
        reader.acquireLatestImage()
      } catch (_: Exception) {
        postFrameFailure(reader)
        return
      } ?: return
      val hardwareBuffer = try {
        image.hardwareBuffer
      } catch (_: Exception) {
        image.close()
        postFrameFailure(reader)
        return
      }
      if (hardwareBuffer == null) {
        image.close()
        postFrameFailure(reader)
        return
      }
      var accepted = false
      try {
        // Stamp the frame with its media time as it is deposited, so the consumer simulates
        // against the same frame it draws. This runs on the ImageReader thread and ExoPlayer
        // is app-thread-only, so it reads `currentTime` — the volatile 60Hz snapshot
        // extrapolated by wall clock — rather than touching the player. That is up to one
        // ticker interval (~16ms) stale, unlike iOS where the exact item time is available at
        // the pull; still far better than the previous native -> JS -> worklet round trip,
        // and crucially it is now attached to the frame rather than racing it.
        val handle = FrameSourceNative.nativePushFrame(
          providerPtr,
          hardwareBuffer,
          currentTime,
          clipGeneration,
        )
        check(handle != 0L)
        retainedImages[handle] = RetainedImage(image, reader)
        accepted = true
      } catch (_: Exception) {
        postFrameFailure(reader)
      } finally {
        hardwareBuffer.close()
        if (!accepted) image.close()
      }
    }
  }

  private fun postFrameFailure(reader: ImageReader) {
    if (frameFailurePending) return
    frameFailurePending = true
    mainHandler.post {
      val isActive = synchronized(frameDeliveryLock) {
        if (imageReader === reader) {
          true
        } else {
          frameFailurePending = false
          false
        }
      }
      if (isActive) failPlayback()
    }
  }

  private fun closeReader() {
    val reader = synchronized(frameDeliveryLock) {
      val reader = imageReader
      val thread = frameThread
      val bridge = glBridge
      imageReader = null
      glBridge = null
      if (reader != null && thread != null && bridge != null) {
        retiredReaders[reader] = RetiredReader(reader, thread, bridge)
      }
      frameFailurePending = false
      readerWidth = 0
      readerHeight = 0
      reader
    }
    reader?.setOnImageAvailableListener(null, null)
    synchronized(frameDeliveryLock) {
      reader?.let(::closeRetiredReaderIfUnused)
    }
    frameThread = null
    frameHandler = null
  }

  fun releaseFrame(handle: Long) {
    synchronized(frameDeliveryLock) {
      val retained = retainedImages.remove(handle) ?: return
      retained.image.close()
      closeRetiredReaderIfUnused(retained.reader)
    }
  }

  private fun closeRetiredReaderIfUnused(reader: ImageReader) {
    if (retainedImages.values.any { it.reader === reader }) return
    val retired = retiredReaders.remove(reader) ?: return
    // The bridge posts its EGL teardown to the frames thread, so release it
    // before that thread is asked to quit; quitSafely drains posted work.
    retired.bridge.release()
    retired.reader.close()
    retired.thread.quitSafely()
  }

  private fun releaseAllFrames() {
    synchronized(frameDeliveryLock) {
      retainedImages.values.forEach { it.image.close() }
      retainedImages.clear()
      retiredReaders.values.forEach {
        it.bridge.release()
        it.reader.close()
        it.thread.quitSafely()
      }
      retiredReaders.clear()
    }
  }

  private fun teardown() {
    mainHandler.removeCallbacks(timeTicker)
    player?.release()
    player = null
    currentUri = null
    closeReader()
    FrameSourceNative.nativeClearLatest(providerPtr)
    timeSnapshot = TimeSnapshot(0.0, 0L, 0.0, false)
  }

  private fun failPlayback() {
    if (released) return
    loadGeneration += 1
    teardown()
    delegate?.onStatusChange("error")
  }

  // MARK: - Probe

  private data class VideoProbe(val width: Int, val height: Int, val rotationDegrees: Int)

  private fun probeVideo(uri: String): VideoProbe {
    val extractor = MediaExtractor()
    try {
      extractor.setDataSource(context, Uri.parse(uri), null)
      for (i in 0 until extractor.trackCount) {
        val format = extractor.getTrackFormat(i)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
        if (mime.startsWith("video/")) {
          val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) {
            format.getInteger(MediaFormat.KEY_ROTATION)
          } else {
            0
          }
          return VideoProbe(
            format.getInteger(MediaFormat.KEY_WIDTH),
            format.getInteger(MediaFormat.KEY_HEIGHT),
            rotation,
          )
        }
      }
      error("VideoTexture: no video track in $uri")
    } finally {
      extractor.release()
    }
  }

  companion object {
    private const val MAX_IMAGES = 5
    private const val TAG = "VideoTexture"

    /// ranchu/goldfish = the Android emulator (gfxstream graphics). Unsupported: gfxstream
    /// can neither Vulkan-import the codec's YUV gralloc buffers (tight NV12 allocation vs
    /// padded host requirement) nor report a usable AHardwareBuffer allocationSize, so
    /// Dawn's shared-texture-memory validation rejects every frame. Checked up front so the
    /// failure is a clear message instead of a native validation error mid-render.
    val IS_EMULATOR: Boolean =
      android.os.Build.HARDWARE == "ranchu" || android.os.Build.HARDWARE == "goldfish"
  }

}
