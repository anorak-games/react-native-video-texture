package expo.modules.videotexture

import android.media.MediaCodecList
import android.media.MediaFormat
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import expo.modules.kotlin.records.Field
import expo.modules.kotlin.records.Record

class VideoFormatQueryRecord : Record {
  @Field var codec: String = ""
  @Field var width: Int = 0
  @Field var height: Int = 0
  @Field var fps: Double = 0.0
}

class VideoFormatQueryErrorRecord : Record {
  @Field var kind: String = ""
  @Field var message: String = ""
}

class VideoFormatSupportRecord : Record {
  @Field var supported: Boolean = false
  @Field var hardwareAccelerated: Boolean = false
  @Field var sustainedRate: Boolean = false
  @Field var error: VideoFormatQueryErrorRecord? = null
}

@UnstableApi
object VideoFormatSupportQuery {
  fun query(format: VideoFormatQueryRecord): VideoFormatSupportRecord {
    val media3Format = Format.Builder()
      .setCodecs(format.codec)
      .setWidth(format.width)
      .setHeight(format.height)
      .build()
    val mime = MimeTypes.getMediaMimeType(format.codec)
      ?.takeIf { it == MimeTypes.VIDEO_H264 || it == MimeTypes.VIDEO_H265 }
      ?: return invalid("Unsupported codec string: ${format.codec}")
    val profileLevel = MediaCodecUtil.getCodecProfileAndLevel(
      media3Format.buildUpon().setSampleMimeType(mime).build(),
    ) ?: return invalid("Malformed codec string: ${format.codec}")

    val discoveryFormat = MediaFormat.createVideoFormat(mime, format.width, format.height).apply {
      setInteger(MediaFormat.KEY_PROFILE, profileLevel.first)
      setInteger(MediaFormat.KEY_LEVEL, profileLevel.second)
    }
    val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
    val decoderName = codecList.findDecoderForFormat(discoveryFormat) ?: return unsupported()
    val decoder = codecList.codecInfos.firstOrNull { it.name == decoderName }
      ?: error("Decoder $decoderName disappeared from the codec list")
    val capabilities = decoder.getCapabilitiesForType(mime).videoCapabilities
      ?: error("Decoder $decoderName has no video capabilities for $mime")
    val performancePoints = capabilities.supportedPerformancePoints
    val rateFormat = MediaFormat(discoveryFormat).apply {
      setFloat(MediaFormat.KEY_FRAME_RATE, format.fps.toFloat())
    }
    val sustainedRate = when {
      performancePoints == null -> capabilities.areSizeAndRateSupported(
        format.width,
        format.height,
        format.fps,
      )
      performancePoints.isEmpty() -> false
      else -> performancePoints.any { it.covers(rateFormat) }
    }

    return VideoFormatSupportRecord().apply {
      supported = true
      hardwareAccelerated = decoder.isHardwareAccelerated
      this.sustainedRate = sustainedRate
    }
  }

  fun platformError(caught: Exception): VideoFormatSupportRecord =
    VideoFormatSupportRecord().apply {
      error = VideoFormatQueryErrorRecord().apply {
        kind = "platform-error"
        message = caught.message ?: caught.javaClass.simpleName
      }
    }

  private fun invalid(message: String): VideoFormatSupportRecord =
    VideoFormatSupportRecord().apply {
      error = VideoFormatQueryErrorRecord().apply {
        kind = "invalid-candidate"
        this.message = message
      }
    }

  private fun unsupported() = VideoFormatSupportRecord()
}
