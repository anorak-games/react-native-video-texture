package expo.modules.videotexture

import android.hardware.HardwareBuffer

/// JNI bindings for the C++ frame provider (AndroidFrameProvider + the shared
/// FrameSourceHostObject). Pointers are opaque heap shared_ptr holders.
object FrameSourceNative {
  init {
    System.loadLibrary("react-native-video-texture")
  }

  external fun nativeCreate(pixelFormat: String): Long
  external fun nativeDestroy(providerPtr: Long)
  external fun nativeInstall(runtimePtr: Long, providerPtr: Long, key: String)
  external fun nativeAttachCommandTarget(providerPtr: Long, target: VideoTexturePlayer)
  external fun nativeDetachCommandTarget(providerPtr: Long)
  /// `ptsSec` is the frame's forward media time and `generation` its clip generation; both
  /// travel with the buffer so a consumer can never simulate against a different frame than
  /// the one it draws.
  external fun nativePushFrame(
    providerPtr: Long,
    hardwareBuffer: HardwareBuffer,
    ptsSec: Double,
    generation: Long,
  ): Long
  external fun nativeClearLatest(providerPtr: Long)
  external fun nativeUpdateTransport(
    providerPtr: Long,
    uri: String?,
    status: Int,
    statusSeq: Long,
    errorSeq: Long,
    errorMessage: String?,
    durationSec: Double,
    actualRate: Double,
    generation: Long,
  )
}
