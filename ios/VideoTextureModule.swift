import ExpoModulesCore

public class VideoTextureModule: Module {
  public func definition() -> ModuleDefinition {
    Name("VideoTexture")

    // Pre-build the reversed composition for boomerang looping on the given URI. Call this
    // after the clip file is cached so the first play finds the reversed file ready, avoiding
    // the `renderReversed` stall that would otherwise fire mid-playback on first play.
    // Resolves true on success, false on failure. Idempotent: cached builds are reused.
    AsyncFunction("prebuildBoomerang") { (uri: String, promise: Promise) in
      let url: URL
      if uri.hasPrefix("file://") {
        url = URL(fileURLWithPath: String(uri.dropFirst(7)))
      } else if uri.hasPrefix("/") {
        url = URL(fileURLWithPath: uri)
      } else if let remote = URL(string: uri) {
        url = remote
      } else {
        promise.resolve(false)
        return
      }
      BoomerangComposition.build(sourceURL: url) { built in
        promise.resolve(built != nil)
      }
    }

    // Headless player for the WebGPU consumer. Frames flow to the render
    // worklet through the JSI frame source installed under
    // globalThis.__videoTextureFrameSources[frameSourceKey].
    Class(VideoTexturePlayer.self) {
      Constructor { (pixelFormat: String?) -> VideoTexturePlayer in
        VideoTexturePlayer(pixelFormat: pixelFormat ?? "nv12")
      }

      Property("currentTimeSec") { (player: VideoTexturePlayer) in
        player.currentTimeSec
      }

      Property("frameSourceKey") { (player: VideoTexturePlayer) in
        player.frameSourceKey
      }

      Property("volume") { (player: VideoTexturePlayer) in
        player.volume
      }
      .set { (player: VideoTexturePlayer, volume: Double) in
        player.volume = volume
      }

      Function("installFrameSource") { [weak self] (player: VideoTexturePlayer) in
        guard let runtime = try? self?.appContext?.runtime else { return }
        player.installFrameSource(runtime: runtime)
      }

      Function("loadClip") { (player: VideoTexturePlayer, options: LoadClipOptions) in
        player.loadClip(options)
      }

      Function("setPaused") { (player: VideoTexturePlayer, paused: Bool) in
        player.setPaused(paused)
      }

      Function("setRate") { (player: VideoTexturePlayer, rate: Double) in
        player.setRate(rate)
      }

      Function("rampRate") { (player: VideoTexturePlayer, rate: Double, durationMs: Double) in
        player.rampRate(rate, durationMs: durationMs)
      }

      Function("seek") { (player: VideoTexturePlayer, sec: Double) in
        player.seek(to: sec)
      }
    }
  }
}
