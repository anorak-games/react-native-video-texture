package expo.modules.videotexture

import android.content.Context
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/// Builds the boomerang playback assets — Android analogue of ios/BoomerangComposition.swift.
///
/// ExoPlayer cannot play in reverse and Android has no AVComposition, so the boomerang is
/// realised as a TWO-ITEM PLAYLIST: [original, reversed-file] with REPEAT_MODE_ALL — video
/// plays fwd,rev,fwd,rev…, audio always forward (the reversed file carries the ORIGINAL
/// forward audio). The reversed video is pre-rendered GOP-by-GOP with MediaCodec (bounded
/// memory) and cached in cacheDir keyed by the source URI, so it is built at most once.
object BoomerangComposition {
  data class Built(val reversedUri: Uri, val forwardLenSec: Double)

  private val uriLocks = ConcurrentHashMap<String, Any>()

  /// Synchronous build (call from a background thread). Returns null on any failure —
  /// callers must stay on the plain forward loop in that case (iOS-documented behavior).
  fun buildSync(context: Context, sourceUri: String): Built? {
    val lock = uriLocks.getOrPut(sourceUri) { Any() }
    synchronized(lock) {
      return try {
        buildLocked(context, sourceUri)
      } catch (_: Throwable) {
        null
      }
    }
  }

  private fun buildLocked(context: Context, sourceUri: String): Built? {
    val probe = probeSource(context, sourceUri) ?: return null

    val cached = cachedReversedFile(context, sourceUri)
    if (!cached.exists()) {
      val tmp = File(cached.parentFile, "${cached.name}.tmp")
      tmp.delete()
      try {
        renderReversed(context, sourceUri, probe, tmp)
        check(tmp.renameTo(cached)) { "boomerang: rename failed" }
      } finally {
        tmp.delete()
      }
    }
    return Built(Uri.fromFile(cached), probe.durationSec)
  }

  /// Stable cache file keyed by the source URI hash. The filename version identifies the render
  /// format and must change when cached output compatibility changes.
  private fun cachedReversedFile(context: Context, sourceUri: String): File {
    val digest = MessageDigest.getInstance("SHA-1").digest(sourceUri.toByteArray())
    val key = digest.joinToString("") { "%02x".format(it) }.take(20)
    return File(context.cacheDir, "boomerang-rev-v2-$key.mp4")
  }

  // MARK: - Probe

  private class SourceProbe(
    val videoTrack: Int,
    val audioTrack: Int,
    val width: Int,
    val height: Int,
    val durationSec: Double,
  )

  private fun probeSource(context: Context, sourceUri: String): SourceProbe? {
    val extractor = MediaExtractor()
    try {
      extractor.setDataSource(context, Uri.parse(sourceUri), null)
      var video = -1
      var audio = -1
      var width = 0
      var height = 0
      var durationUs = 0L
      for (i in 0 until extractor.trackCount) {
        val format = extractor.getTrackFormat(i)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
        if (mime.startsWith("video/") && video < 0) {
          video = i
          width = format.getInteger(MediaFormat.KEY_WIDTH)
          height = format.getInteger(MediaFormat.KEY_HEIGHT)
          durationUs = format.getLong(MediaFormat.KEY_DURATION)
        } else if (mime.startsWith("audio/") && audio < 0) {
          audio = i
        }
      }
      if (video < 0 || durationUs <= 0) {
        return null
      }
      return SourceProbe(video, audio, width, height, durationUs / 1_000_000.0)
    } finally {
      extractor.release()
    }
  }

  // MARK: - Reverse render

  /// Decode GOP-by-GOP from the LAST group to the first (bounded memory: one GOP of I420
  /// frames at a time), re-encode in reversed presentation order with fresh monotonic PTS,
  /// and mux with the source's forward audio copied compressed (no audio re-encode).
  private fun renderReversed(context: Context, sourceUri: String, probe: SourceProbe, out: File) {
    val spoolFile = File(out.parentFile, "${out.name}.gop.raw")
    val extractor = MediaExtractor()
    var muxerToRelease: MediaMuxer? = null
    var decoderToRelease: MediaCodec? = null
    var encoderToRelease: MediaCodec? = null
    var muxerStarted = false
    var decoderStarted = false
    var encoderStarted = false
    try {
      extractor.setDataSource(context, Uri.parse(sourceUri), null)
      extractor.selectTrack(probe.videoTrack)
      val videoFormat = extractor.getTrackFormat(probe.videoTrack)
      val mime = requireNotNull(videoFormat.getString(MediaFormat.KEY_MIME))

      // Index pass: decode-order sample times + sync positions. No decoding.
      val sampleTimesUs = ArrayList<Long>(1024)
      val syncPositions = ArrayList<Int>(64)
      while (true) {
        val t = extractor.sampleTime
        if (t < 0) break
        if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
          syncPositions.add(sampleTimesUs.size)
        }
        sampleTimesUs.add(t)
        extractor.advance()
      }
      check(sampleTimesUs.isNotEmpty() && syncPositions.isNotEmpty()) { "boomerang: empty video track" }
      val sortedPts = sampleTimesUs.sorted()
      val frameDurUs = if (sortedPts.size > 1) {
        (sortedPts.last() - sortedPts.first()) / (sortedPts.size - 1)
      } else {
        33_333L
      }
      val fps = (1_000_000.0 / frameDurUs).toInt().coerceIn(1, 120)

      val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        .also { muxerToRelease = it }
      val audioTrackOut = if (probe.audioTrack >= 0) {
        muxer.addTrack(extractor.getTrackFormat(probe.audioTrack))
      } else {
        -1
      }

      val decoder = MediaCodec.createDecoderByType(mime)
        .also { decoderToRelease = it }
      videoFormat.setInteger(
        MediaFormat.KEY_COLOR_FORMAT,
        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
      )
      decoder.configure(videoFormat, null, null, 0)
      decoder.start()
      decoderStarted = true

      // Keep both directions at source resolution so image quality remains consistent at each
      // turnaround. Peak memory stays bounded because rendering proceeds one GOP at a time.
      val (renderWidth, renderHeight) = evenSize(probe.width, probe.height)
      val encFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, renderWidth, renderHeight)
      encFormat.setInteger(
        MediaFormat.KEY_COLOR_FORMAT,
        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
      )
      // Match the source bitrate where it is declared. The fallback has to scale with the frame:
      // a fixed 8 Mbps is fine at 720p but would make a 4K reverse leg mushy, which defeats
      // rendering it at source resolution in the first place. ~0.15 bits/pixel/frame is a
      // reasonable h264 target (≈37 Mbps at 2160p30, ≈4 Mbps at 720p30).
      val srcBitRate = if (videoFormat.containsKey(MediaFormat.KEY_BIT_RATE)) {
        videoFormat.getInteger(MediaFormat.KEY_BIT_RATE)
      } else {
        (renderWidth.toLong() * renderHeight * fps * 15 / 100).coerceIn(2_000_000L, 60_000_000L).toInt()
      }
      encFormat.setInteger(MediaFormat.KEY_BIT_RATE, srcBitRate)
      encFormat.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
      encFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
      // Stamp explicit color aspects into the bitstream VUI. Sources recorded
      // by the app declare none (coded.vui.color.* = 0), which leaves decoders
      // guessing the matrix — Samsung guesses BT.2020 for un-tagged 4K SDR.
      // Copy from the source when it declares aspects, else pin SDR BT.709.
      fun copyColorAspect(key: String, default: Int) {
        val value = if (videoFormat.containsKey(key)) videoFormat.getInteger(key) else default
        encFormat.setInteger(key, value)
      }
      copyColorAspect(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
      copyColorAspect(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
      copyColorAspect(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
      val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        .also { encoderToRelease = it }
      encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
      encoder.start()
      encoderStarted = true

      var videoTrackOut = -1
      var outFrameIndex = 0L
      val encInfo = MediaCodec.BufferInfo()

      fun drainEncoder(endOfStream: Boolean) {
        while (true) {
          val idx = encoder.dequeueOutputBuffer(encInfo, if (endOfStream) 10_000L else 0L)
          when {
            idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
              check(videoTrackOut < 0) { "boomerang: encoder format changed twice" }
              videoTrackOut = muxer.addTrack(encoder.outputFormat)
              muxer.start()
              muxerStarted = true
            }
            idx >= 0 -> {
              val buf = requireNotNull(encoder.getOutputBuffer(idx))
              if (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && encInfo.size > 0) {
                check(muxerStarted) { "boomerang: encoder output before muxer start" }
                muxer.writeSampleData(videoTrackOut, buf, encInfo)
              }
              val eos = encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
              encoder.releaseOutputBuffer(idx, false)
              if (eos) return
            }
            else -> if (!endOfStream) return // TRY_AGAIN while feeding: come back later
          }
        }
      }

      fun encodeFrame(frame: ByteArray?) {
        // null = end of stream
        while (true) {
          val inIdx = encoder.dequeueInputBuffer(10_000L)
          if (inIdx < 0) {
            drainEncoder(false)
            continue
          }
          if (frame == null) {
            encoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
          } else {
            val image = requireNotNull(encoder.getInputImage(inIdx)) { "boomerang: encoder has no input Image" }
            fillImageFromI420(image, frame, renderWidth, renderHeight)
            val ptsUs = outFrameIndex * frameDurUs
            outFrameIndex += 1
            encoder.queueInputBuffer(inIdx, 0, renderWidth * renderHeight * 3 / 2, ptsUs, 0)
          }
          drainEncoder(false)
          return
        }
      }

      val frameSize = renderWidth * renderHeight * 3 / 2
      val frameBuf = ByteArray(frameSize)
      try {
        java.io.RandomAccessFile(spoolFile, "rw").use { spool ->
          // GOPs last → first; each GOP decoded forward into the spool file (one
          // frame slot per output index), then emitted reversed via seeks — memory
          // stays at ~2 frames regardless of GOP length.
          for (g in syncPositions.indices.reversed()) {
            val startSample = syncPositions[g]
            val endSample = if (g + 1 < syncPositions.size) syncPositions[g + 1] else sampleTimesUs.size
            val ptsInArrival = decodeGopToSpool(
              extractor,
              decoder,
              sampleTimesUs[startSample],
              endSample - startSample,
              spool,
              frameBuf,
              renderWidth,
              renderHeight,
            )
            val order = ptsInArrival.indices.sortedBy { ptsInArrival[it] }
            for (o in order.indices.reversed()) {
              spool.seek(order[o].toLong() * frameSize)
              spool.readFully(frameBuf)
              encodeFrame(frameBuf)
            }
          }
        }
        encodeFrame(null)
        drainEncoder(true)

        // Audio passthrough: forward audio, compressed samples copied as-is.
        if (audioTrackOut >= 0) {
          extractor.unselectTrack(probe.videoTrack)
          extractor.selectTrack(probe.audioTrack)
          extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
          val buf = ByteBuffer.allocate(1 shl 20)
          val info = MediaCodec.BufferInfo()
          while (true) {
            val size = extractor.readSampleData(buf, 0)
            if (size < 0) break
            info.set(
              0,
              size,
              extractor.sampleTime,
              if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                MediaCodec.BUFFER_FLAG_KEY_FRAME
              } else {
                0
              },
            )
            muxer.writeSampleData(audioTrackOut, buf, info)
            extractor.advance()
          }
        }
      } finally {
        spoolFile.delete()
      }
    } finally {
      spoolFile.delete()
      if (encoderStarted) runCatching { encoderToRelease?.stop() }
      runCatching { encoderToRelease?.release() }
      if (decoderStarted) runCatching { decoderToRelease?.stop() }
      runCatching { decoderToRelease?.release() }
      if (muxerStarted) runCatching { muxerToRelease?.stop() }
      runCatching { muxerToRelease?.release() }
      runCatching { extractor.release() }
    }
  }

  /// Decode one GOP: seek to its sync sample, feed `sampleCount` decode-order samples + EOS,
  /// write each output frame (tight I420) to its arrival-index slot in the spool file,
  /// flush for the next GOP. Returns the arrival-order presentation timestamps.
  private fun decodeGopToSpool(
    extractor: MediaExtractor,
    decoder: MediaCodec,
    gopStartUs: Long,
    sampleCount: Int,
    spool: java.io.RandomAccessFile,
    frameBuf: ByteArray,
    renderWidth: Int,
    renderHeight: Int,
  ): List<Long> {
    extractor.seekTo(gopStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
    val ptsInArrival = ArrayList<Long>(sampleCount)
    val info = MediaCodec.BufferInfo()
    var fed = 0
    var inputDone = false
    var outputDone = false
    while (!outputDone) {
      if (!inputDone) {
        val inIdx = decoder.dequeueInputBuffer(10_000L)
        if (inIdx >= 0) {
          if (fed < sampleCount) {
            val buf = requireNotNull(decoder.getInputBuffer(inIdx))
            val size = extractor.readSampleData(buf, 0)
            if (size < 0) {
              decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
              inputDone = true
            } else {
              decoder.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
              extractor.advance()
              fed += 1
            }
          } else {
            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            inputDone = true
          }
        }
      }
      val outIdx = decoder.dequeueOutputBuffer(info, 10_000L)
      if (outIdx >= 0) {
        if (info.size > 0) {
          val image = requireNotNull(decoder.getOutputImage(outIdx)) { "boomerang: decoder has no output Image" }
          copyImageToI420(image, frameBuf, renderWidth, renderHeight)
          spool.seek(ptsInArrival.size.toLong() * frameBuf.size)
          spool.write(frameBuf)
          ptsInArrival.add(info.presentationTimeUs)
        }
        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
        decoder.releaseOutputBuffer(outIdx, false)
        if (eos) outputDone = true
      }
    }
    decoder.flush()
    return ptsInArrival
  }

  // MARK: - I420 plane copies (tight reusable buffer <-> strided MediaCodec Image)

  private fun copyImageToI420(image: Image, out: ByteArray, width: Int, height: Int) {
    val sourceWidth = image.width
    val sourceHeight = image.height
    var offset = 0
    for (p in 0 until 3) {
      val plane = image.planes[p]
      val sourcePlaneWidth = if (p == 0) sourceWidth else sourceWidth / 2
      val sourcePlaneHeight = if (p == 0) sourceHeight else sourceHeight / 2
      val planeWidth = if (p == 0) width else width / 2
      val planeHeight = if (p == 0) height else height / 2
      val rowStride = plane.rowStride
      val pixelStride = plane.pixelStride
      val src = plane.buffer
      for (row in 0 until planeHeight) {
        val sourceRow = row * sourcePlaneHeight / planeHeight
        for (column in 0 until planeWidth) {
          val sourceColumn = column * sourcePlaneWidth / planeWidth
          out[offset] = src.get(sourceRow * rowStride + sourceColumn * pixelStride)
          offset += 1
        }
      }
    }
  }

  /// Round down to even dimensions — h264 requires them. No scaling: the reversed leg renders
  /// at source resolution (see the call site).
  private fun evenSize(width: Int, height: Int): Pair<Int, Int> {
    fun even(value: Int): Int = maxOf(2, value and -2)
    return Pair(even(width), even(height))
  }

  private fun fillImageFromI420(image: Image, data: ByteArray, w: Int, h: Int) {
    var offset = 0
    for (p in 0 until 3) {
      val plane = image.planes[p]
      val pw = if (p == 0) w else w / 2
      val ph = if (p == 0) h else h / 2
      val rowStride = plane.rowStride
      val pixelStride = plane.pixelStride
      val dst = plane.buffer
      for (r in 0 until ph) {
        if (pixelStride == 1) {
          dst.position(r * rowStride)
          dst.put(data, offset, pw)
          offset += pw
        } else {
          for (col in 0 until pw) {
            dst.position(r * rowStride + col * pixelStride)
            dst.put(data[offset])
            offset += 1
          }
        }
      }
    }
  }
}
