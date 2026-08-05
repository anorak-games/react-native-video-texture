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

/// One-shot boomerang render — Android analogue of ios/BoomerangWriter.swift.
///
/// Writes `[forward 0..N-1][reverse N-2..1]` (both duplicate endpoint frames dropped, so
/// neither the turnaround nor the loop seam holds a frame) plus the source's forward audio
/// twice, to a single self-contained mp4 that loops seamlessly with a plain `'loop'` mode.
/// It simulates the eventual server-side pre-bake; nothing here touches playback.
///
/// Both legs go through ONE encoder instance: MediaMuxer allows a single addTrack (one
/// SPS/PPS) per track, so the forward leg cannot be sample-copied from the source while the
/// reverse leg is freshly encoded. Cost: the forward leg is a second-generation encode,
/// mitigated by rendering at source resolution and source bitrate. The reverse leg is
/// decoded GOP-by-GOP from the last group to the first through a raw spool file, so peak
/// memory stays at ~2 frames regardless of resolution or clip length (a whole-clip reverse
/// would need gigabytes at 2160p).
object BoomerangWriter {

  /// Render `sourceUri` as a boomerang to `outputPath`, overwriting any existing file.
  /// Synchronous — call from a background thread. Throws with a message on any failure.
  fun writeSync(context: Context, sourceUri: String, outputPath: String) {
    val probe = probeSource(context, sourceUri)
    val out = File(outputPath)
    out.parentFile?.mkdirs()
    val tmp = File(out.parentFile, "${out.name}.tmp")
    tmp.delete()
    try {
      renderBoomerang(context, sourceUri, probe, tmp)
      out.delete()
      check(tmp.renameTo(out)) { "makeBoomerang: rename to $outputPath failed" }
    } finally {
      tmp.delete()
    }
  }

  // MARK: - Probe

  private class SourceProbe(
    val videoTrack: Int,
    val audioTrack: Int,
    val width: Int,
    val height: Int,
  )

  private fun probeSource(context: Context, sourceUri: String): SourceProbe {
    val extractor = MediaExtractor()
    try {
      extractor.setDataSource(context, Uri.parse(sourceUri), null)
      var video = -1
      var audio = -1
      var width = 0
      var height = 0
      for (i in 0 until extractor.trackCount) {
        val format = extractor.getTrackFormat(i)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
        if (mime.startsWith("video/") && video < 0) {
          video = i
          width = format.getInteger(MediaFormat.KEY_WIDTH)
          height = format.getInteger(MediaFormat.KEY_HEIGHT)
        } else if (mime.startsWith("audio/") && audio < 0) {
          audio = i
        }
      }
      check(video >= 0) { "makeBoomerang: no video track in $sourceUri" }
      return SourceProbe(video, audio, width, height)
    } finally {
      extractor.release()
    }
  }

  // MARK: - Render

  private fun renderBoomerang(context: Context, sourceUri: String, probe: SourceProbe, out: File) {
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
      check(sampleTimesUs.isNotEmpty() && syncPositions.isNotEmpty()) { "makeBoomerang: empty video track" }
      check(sampleTimesUs.size >= 2) { "makeBoomerang: source has fewer than 2 video frames" }
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

      // Keep both legs at source resolution so image quality remains consistent at the
      // turnaround. Peak memory stays bounded because rendering proceeds one GOP at a time.
      val (renderWidth, renderHeight) = evenSize(probe.width, probe.height)
      val encFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, renderWidth, renderHeight)
      encFormat.setInteger(
        MediaFormat.KEY_COLOR_FORMAT,
        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
      )
      // Match the source bitrate where it is declared. The fallback has to scale with the frame:
      // a fixed 8 Mbps is fine at 720p but would make a 4K render mushy, which defeats
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
              check(videoTrackOut < 0) { "makeBoomerang: encoder format changed twice" }
              videoTrackOut = muxer.addTrack(encoder.outputFormat)
              muxer.start()
              muxerStarted = true
            }
            idx >= 0 -> {
              val buf = requireNotNull(encoder.getOutputBuffer(idx))
              if (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && encInfo.size > 0) {
                check(muxerStarted) { "makeBoomerang: encoder output before muxer start" }
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
            val image = requireNotNull(encoder.getInputImage(inIdx)) { "makeBoomerang: encoder has no input Image" }
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
      // Counted from what pass 1 actually decodes (not container samples), so the pass-2
      // endpoint guard and the audio leg duration agree with the frames that were written.
      var totalSourceFrames = 0
      try {
        java.io.RandomAccessFile(spoolFile, "rw").use { spool ->
          // Pass 1 (forward): GOPs first → last, each GOP decoded into the spool file (one
          // frame slot per arrival index), then emitted in presentation order via seeks.
          for (g in syncPositions.indices) {
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
            for (arrival in order) {
              spool.seek(arrival.toLong() * frameSize)
              spool.readFully(frameBuf)
              encodeFrame(frameBuf)
              totalSourceFrames += 1
            }
          }
          check(totalSourceFrames >= 2) { "makeBoomerang: decoded fewer than 2 video frames" }

          // Pass 2 (reverse): GOPs last → first, each GOP's frames emitted in reverse
          // presentation order — global presentation index N-1 down to 0. The two duplicate
          // endpoint frames are dropped by a GLOBAL index guard (emit only 1..N-2): the
          // turnaround never repeats frame N-1 and the loop seam never repeats frame 0.
          // Guarding per-GOP instead would silently drop a frame at every GOP boundary.
          var srcIndex = totalSourceFrames - 1
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
              if (srcIndex in 1 until totalSourceFrames - 1) {
                spool.seek(order[o].toLong() * frameSize)
                spool.readFully(frameBuf)
                encodeFrame(frameBuf)
              }
              srcIndex -= 1
            }
          }
        }
        encodeFrame(null)
        drainEncoder(true)

        // Audio: the source's forward audio, twice, compressed samples copied as-is (never
        // re-encoded). Copy 2 starts where the output's forward video leg ends. MediaMuxer
        // requires strictly monotonic PTS per track, so BOTH copies are trimmed to the
        // forward-leg duration (an audio track longer than the video leg would otherwise
        // start copy 2 before copy 1 ended), and copy 2 additionally stops at the total
        // video duration. Sample times are normalized so audio that does not start at 0
        // cannot break monotonicity either.
        if (audioTrackOut >= 0) {
          val forwardLegDurUs = totalSourceFrames.toLong() * frameDurUs
          val totalVideoDurUs = (2L * totalSourceFrames - 2L) * frameDurUs
          extractor.unselectTrack(probe.videoTrack)
          extractor.selectTrack(probe.audioTrack)
          val buf = ByteBuffer.allocate(1 shl 20)
          val info = MediaCodec.BufferInfo()
          for (copy in 0 until 2) {
            val offsetUs = copy * forwardLegDurUs
            val limitUs = if (copy == 0) forwardLegDurUs else totalVideoDurUs - forwardLegDurUs
            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            var basePtsUs = -1L
            while (true) {
              val size = extractor.readSampleData(buf, 0)
              if (size < 0) break
              val sampleTimeUs = extractor.sampleTime
              if (basePtsUs < 0) basePtsUs = sampleTimeUs
              val relUs = sampleTimeUs - basePtsUs
              if (relUs >= limitUs) break
              info.set(
                0,
                size,
                relUs + offsetUs,
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
          val image = requireNotNull(decoder.getOutputImage(outIdx)) { "makeBoomerang: decoder has no output Image" }
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

  /// Round down to even dimensions — h264 requires them. No scaling: both legs render
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
