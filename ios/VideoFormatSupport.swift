import CoreMedia
import ExpoModulesCore
import VideoToolbox

struct VideoFormatQueryRecord: Record {
  @Field var codec: String = ""
  @Field var width: Int = 0
  @Field var height: Int = 0
  @Field var fps: Double = 0
}

struct VideoFormatQueryErrorRecord: Record {
  @Field var kind: String = ""
  @Field var message: String = ""
}

struct VideoFormatSupportRecord: Record {
  @Field var supported: Bool = false
  @Field var hardwareAccelerated: Bool = false
  @Field var sustainedRate: Bool = false
  @Field var error: VideoFormatQueryErrorRecord?
}

enum VideoFormatSupportQuery {
  static func query(_ format: VideoFormatQueryRecord) -> VideoFormatSupportRecord {
    let codecType: CMVideoCodecType
    if format.codec.hasPrefix("avc1.") {
      codecType = kCMVideoCodecType_H264
    } else if format.codec.hasPrefix("hvc1.") {
      codecType = kCMVideoCodecType_HEVC
    } else {
      return invalid("Unsupported codec string: \(format.codec)")
    }

    // Before download, iOS exposes codec-family hardware availability but not
    // per-rendition profile, dimensions, or sustained-rate capabilities.
    let hardwareDecode = VTIsHardwareDecodeSupported(codecType)
    var result = VideoFormatSupportRecord()
    result.supported = hardwareDecode
    result.hardwareAccelerated = hardwareDecode
    result.sustainedRate = hardwareDecode
    return result
  }

  static func platformError(_ error: Error) -> VideoFormatSupportRecord {
    var result = VideoFormatSupportRecord()
    var queryError = VideoFormatQueryErrorRecord()
    queryError.kind = "platform-error"
    queryError.message = error.localizedDescription
    result.error = queryError
    return result
  }

  private static func invalid(_ message: String) -> VideoFormatSupportRecord {
    var result = VideoFormatSupportRecord()
    var error = VideoFormatQueryErrorRecord()
    error.kind = "invalid-candidate"
    error.message = message
    result.error = error
    return result
  }
}
