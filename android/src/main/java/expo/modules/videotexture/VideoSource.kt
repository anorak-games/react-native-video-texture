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
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
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
  private var armedClipEndSec: Double? = null
  /// Absolute-file-time bound of the playable region; <= 0 = none. `'off'` mode enforces it
  /// with an exact PlayerMessage at the bound (full item, absolute positions — no clipping,
  /// so the hot same-URI segment swap stays a pure seek). `'loop'` mode enforces it with
  /// MediaItem.ClippingConfiguration (see loadUri).
  private var clipEndSec: Double = -1.0
  /// Under a loop-region ClippingConfiguration ExoPlayer reports CLIP-RELATIVE positions;
  /// this offset (= region start) translates them back to absolute file time for
  /// `currentTime` / frame stamping, and is subtracted from absolute `seek` targets.
  /// 0 outside loop-region mode.
  private var clipOffsetSec = 0.0
  /// Exact end-of-region trigger for `'off'` mode. deleteAfterDelivery=false so a backwards
  /// seek re-arms it naturally; cancelled on load/teardown.
  private var endMessage: PlayerMessage? = null
  /// The region the current loop playlist was clipped to (whole file = (0, -1)); a
  /// loop-region change needs a fresh playlist, unlike an 'off'-region change.
  private var installedLoopStartSec = 0.0
  private var installedLoopEndSec = -1.0
  /// The `'off'`-mode region bound the frame gate is armed at; -1 = no gate (loop mode
  /// enforces its region natively via ClippingConfiguration). Read on the playback thread
  /// by the VideoFrameMetadataListener installed in `ensurePlayer`.
  @Volatile private var offModeEndGateSec = -1.0
  /// Closed (true) when the frame about to be rendered is AT or PAST the region bound —
  /// in a baked file that frame is the NEXT segment's (segments are [start, end), so the
  /// boundary frame at endSec is already the next clip's IDR). The end PlayerMessage +
  /// pause land asynchronously (main-looper delivery, then a hop back to the playback
  /// thread), so such frames DO get rendered; the ImageReader deposit drops them while
  /// this is closed and the renderer holds the last in-region frame. Driven by each
  /// frame's own EXACT media pts — extrapolated position stamps carry ~16ms of jitter,
  /// which cannot separate two frames one frame-interval apart.
  @Volatile private var frameGateClosed = false
  @Volatile private var endedReported = false
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

  /// Armed together with `armClipStart` (once per clip generation), before `loadUri`.
  fun armClipEnd(sec: Double) {
    armedClipEndSec = if (sec > 0) sec else -1.0
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
    // A loop playlist's ClippingConfiguration is immutable, so a loop-REGION change needs a
    // fresh playlist; an 'off'-region change is just a message re-arm and stays on the hot
    // seek path. (Null armed values = generation unchanged = region unchanged.)
    val loopRegionUnchanged = loopMode != "loop" ||
      (
        (armedClipStartSec?.let { kotlin.math.abs(it - installedLoopStartSec) < 0.001 } != false) &&
          (armedClipEndSec?.let { kotlin.math.abs(it - installedLoopEndSec) < 0.001 } != false)
        )
    if (uri == currentUri && player != null && loopMode == installedLoopMode && loopRegionUnchanged) {
      // Reuse path: same clip stays loaded; a pending armed start is applied as a seek and a
      // pending armed end re-bounds the region. THE hot path for a baked-file segment swap.
      // (Guarded on the installed loop mode: a mode change needs a fresh playlist shape.)
      endedReported = false
      frameGateClosed = false
      val p = requireNotNull(player)
      // Ordering matters twice here:
      // 1. The armed end must land in `clipEndSec` BEFORE the seek — seek() clamps to the
      //    region bound, and the new region lies entirely beyond the old one for a baked
      //    follow-up (main [0, 15.04] → fu [28.71, 31.46]). Clamping against the STALE
      //    bound seeked to the old main tail and played every intervening segment.
      // 2. The end MESSAGE must be armed AFTER the seek: a PlayerMessage behind the current
      //    playhead (e.g. re-arming the main region while parked at a later follow-up
      //    segment) would deliver immediately and report a spurious end. seekTo updates the
      //    masked position synchronously, so arming after it always places the bound ahead
      //    of the playhead.
      val armedEnd = armedClipEndSec
      armedClipEndSec = null
      if (armedEnd != null) clipEndSec = armedEnd
      if (loopMode != "loop") offModeEndGateSec = clipEndSec
      armedClipStartSec?.let { start ->
        armedClipStartSec = null
        seek(start)
      }
      if (armedEnd != null && loopMode != "loop") armEndMessage(p, armedEnd)
      p.playWhenReady = shouldAutoPlay
      return
    }

    currentUri = uri
    endedReported = false
    frameGateClosed = false
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
          val endSec = armedClipEndSec ?: clipEndSec
          armedClipEndSec = null
          clipEndSec = endSec
          offModeEndGateSec = if (loopMode == "loop") -1.0 else endSec
          endMessage?.cancel()
          endMessage = null
          val item = MediaItem.fromUri(uri)
          if (loopMode == "loop") {
            // Seamless loop: TWO identical items + REPEAT_MODE_ALL, not REPEAT_MODE_ONE.
            // ExoPlayer prewarms the next playlist period, so the item transition is
            // genuinely gapless; REPEAT_MODE_ONE resets the renderer at the wrap and can hitch.
            if (endSec > 0) {
              // Region loop: clip each playlist item to [startSec, endSec] so the prewarmed
              // wrap lands on the region start. The bake guarantees an IDR exactly at the
              // region start (startsAtKeyFrame skips the pre-roll decode). ExoPlayer reports
              // CLIP-RELATIVE positions under clipping — clipOffsetSec translates back.
              val clipped = MediaItem.Builder()
                .setUri(uri)
                .setClippingConfiguration(
                  MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs((startSec * 1000).toLong())
                    .setEndPositionMs((endSec * 1000).toLong())
                    .setStartsAtKeyFrame(true)
                    .build(),
                )
                .build()
              clipOffsetSec = startSec
              installedLoopStartSec = startSec
              installedLoopEndSec = endSec
              p.setMediaItems(listOf(clipped, clipped), 0, 0L)
            } else {
              // Whole-file loop: startSec applies to the FIRST cycle only — the loop must
              // wrap to 0 because a pre-baked loop file's seam is frame(last)→frame(0).
              clipOffsetSec = 0.0
              installedLoopStartSec = 0.0
              installedLoopEndSec = -1.0
              p.setMediaItems(listOf(item, item), 0, (startSec * 1000).toLong())
            }
          } else {
            clipOffsetSec = 0.0
            p.setMediaItem(item, (startSec * 1000).toLong())
          }
          installedLoopMode = loopMode
          p.playWhenReady = shouldAutoPlay
          p.repeatMode = if (loopMode == "loop") Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
          p.playbackParameters = PlaybackParameters(desiredRate.toFloat())
          p.prepare()
          if (loopMode != "loop") armEndMessage(p, endSec)
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
    // Reopen eagerly: the first post-seek render self-corrects the gate anyway (see the
    // frame metadata listener), but a paused player renders exactly one frame on seek —
    // don't let a stale closed gate swallow it.
    frameGateClosed = false
    if (loopMode == "loop") {
      // Target the CURRENT playlist item so a seek stays in this cycle instead of
      // jumping back to item 0. `duration` is per-item under a playlist, and under a
      // region clip both it and the seek target are CLIP-RELATIVE — the caller's target
      // is absolute file time, so translate by the clip offset.
      val relSec = sec - clipOffsetSec
      val durationMs = p.duration
      val clampedSec = if (durationMs != C.TIME_UNSET) {
        min(relSec, durationMs / 1000.0 - 0.1)
      } else {
        relSec
      }
      p.seekTo(p.currentMediaItemIndex, (max(0.0, clampedSec) * 1000).toLong())
      return
    }
    // 'off' mode plays the full item; clamp to the region bound when one is set so a seek
    // cannot park the playhead beyond the reported end.
    val durationMs = p.duration
    var limitSec = if (durationMs != C.TIME_UNSET) durationMs / 1000.0 else Double.MAX_VALUE
    if (clipEndSec > 0) limitSec = min(limitSec, clipEndSec)
    val clampedSec = if (limitSec != Double.MAX_VALUE) min(sec, limitSec - 0.1) else sec
    p.seekTo((max(0.0, clampedSec) * 1000).toLong())
  }

  /// Exact end-of-region trigger for `'off'` mode: an ExoPlayer PlayerMessage delivered on
  /// the main looper when playback reaches the bound — pause + report ended, the same
  /// terminal shape STATE_ENDED produces at real file end. deleteAfterDelivery=false keeps
  /// it armed across backwards seeks; it dies with the player or the next load.
  private fun armEndMessage(p: ExoPlayer, endSec: Double) {
    endMessage?.cancel()
    endMessage = null
    if (endSec <= 0) return
    endMessage = p.createMessage { _, _ ->
      if (player === p && !endedReported) {
        endedReported = true
        p.pause()
        delegate?.onPlayToEnd()
      }
    }
      .setPosition(0, (endSec * 1000).toLong())
      .setLooper(Looper.getMainLooper())
      .setDeleteAfterDelivery(false)
      .send()
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
      // already the sawtooth a loop should report. Under a region clip it is additionally
      // CLIP-RELATIVE; adding the clip offset keeps reported time (and therefore frame
      // ptsSec stamps) in ABSOLUTE file seconds, per the contract.
      val positionSec = p.currentPosition / 1000.0 + clipOffsetSec
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
    val loadControl = DefaultLoadControl.Builder()
      .setBufferDurationsMsForLocalPlayback(
        LOCAL_MIN_BUFFER_MS,
        LOCAL_MAX_BUFFER_MS,
        LOCAL_BUFFER_FOR_PLAYBACK_MS,
        LOCAL_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
      )
      .build()
    val p = ExoPlayer.Builder(context)
      .setLooper(Looper.getMainLooper())
      .setLoadControl(loadControl)
      .build()
    p.setSeekParameters(SeekParameters.CLOSEST_SYNC)
    // Drives the 'off'-mode frame gate: fires on the playback thread just before EACH
    // frame is released to the surface, with the frame's exact media timestamp (for the
    // unclipped 'off'-mode item this is absolute file time; in loop mode the gate is -1
    // and this is a no-op). Every frame sets the gate from ITS OWN pts, so the gate is
    // self-correcting — a re-armed earlier region reopens it with the first legitimate
    // frame's render, before that frame can reach the ImageReader.
    p.setVideoFrameMetadataListener { presentationTimeUs, _, _, _ ->
      val gate = offModeEndGateSec
      frameGateClosed = gate > 0 && presentationTimeUs / 1_000_000.0 >= gate - 0.001
    }
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
      // 'off'-mode region gate (see frameGateClosed): drop frames at/past the region bound
      // (and anything after the end was reported); the renderer holds the last in-region
      // frame — the cover clip swaps already rely on.
      if (endedReported || frameGateClosed) {
        image.close()
        return
      }
      val stampSec = currentTime
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
          stampSec,
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
    endMessage?.cancel()
    endMessage = null
    clipOffsetSec = 0.0
    offModeEndGateSec = -1.0
    frameGateClosed = false
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
    private const val LOCAL_MIN_BUFFER_MS = 250
    private const val LOCAL_MAX_BUFFER_MS = 1_000
    private const val LOCAL_BUFFER_FOR_PLAYBACK_MS = 0
    private const val LOCAL_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 0
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
