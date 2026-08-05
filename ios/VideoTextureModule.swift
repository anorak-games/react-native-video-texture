import ExpoModulesCore

public class VideoTextureModule: Module {
  /// Resolve a uri string to a URL (file path vs remote) — same normalization as
  /// `VideoSource.resolvedURL(for:)`.
  private static func resolvedURL(for uri: String) -> URL? {
    if uri.hasPrefix("file://") || uri.hasPrefix("/") {
      return URL(fileURLWithPath: uri.hasPrefix("file://") ? String(uri.dropFirst(7)) : uri)
    }
    return URL(string: uri)
  }

  public func definition() -> ModuleDefinition {
    Name("VideoTexture")

    // One-shot boomerang render: inputUri → [fwd][rev] file at outputPath (overwritten).
    // Resolves with outputPath; rejects with a message on any failure — callers must not
    // fall back silently, this simulates the eventual server-side pre-bake job.
    AsyncFunction("makeBoomerang") { (inputUri: String, outputPath: String, promise: Promise) in
      guard let inputURL = Self.resolvedURL(for: inputUri) else {
        promise.reject("ERR_MAKE_BOOMERANG", "makeBoomerang: unusable input uri \(inputUri)")
        return
      }
      let outputURL = URL(
        fileURLWithPath: outputPath.hasPrefix("file://")
          ? String(outputPath.dropFirst(7)) : outputPath)
      BoomerangWriter.write(sourceURL: inputURL, outputURL: outputURL) { error in
        if let error {
          promise.reject("ERR_MAKE_BOOMERANG", error.localizedDescription)
        } else {
          promise.resolve(outputPath)
        }
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
