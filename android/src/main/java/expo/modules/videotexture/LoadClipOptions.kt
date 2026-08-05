package expo.modules.videotexture

import expo.modules.kotlin.records.Field
import expo.modules.kotlin.records.Record

/// Options for the atomic clip-load transport call — mirrors ios/VideoTexturePlayer.swift.
class LoadClipOptions : Record {
  @Field var uri: String = ""
  @Field var startSec: Double = 0.0
  /// Absolute-file-time playable-region bound; <= 0 = none (whole file).
  @Field var endSec: Double = -1.0
  @Field var generation: Int = 0
  @Field var loopMode: String = "off"
  @Field var autoPlay: Boolean = true
}
