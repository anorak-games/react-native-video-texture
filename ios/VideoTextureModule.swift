import ExpoModulesCore

public class VideoTextureModule: Module {
  public func definition() -> ModuleDefinition {
    Name("VideoTexture")

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
