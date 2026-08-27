package expo.modules.videotexture

import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import androidx.media3.common.util.UnstableApi

/// Android implementation of the VideoTexture Expo module.
@UnstableApi
class VideoTextureModule : Module() {
  companion object {
    init {
      System.loadLibrary("react-native-video-texture")
    }
  }

  // Weak registry so app-lifecycle events reach live players without pinning them.
  private val players =
    java.util.Collections.newSetFromMap(java.util.WeakHashMap<VideoTexturePlayer, Boolean>())

  override fun definition() = ModuleDefinition {
    Name("VideoTexture")

    OnActivityEntersBackground {
      players.forEach { it.onBackground() }
    }

    OnActivityEntersForeground {
      players.forEach { it.onForeground() }
    }

    AsyncFunction("queryVideoFormatSupport") { formats: List<VideoFormatQueryRecord> ->
      formats.map { format ->
        try {
          VideoFormatSupportQuery.query(format)
        } catch (caught: Exception) {
          VideoFormatSupportQuery.platformError(caught)
        }
      }
    }

    Class(VideoTexturePlayer::class) {
      Constructor { pixelFormat: String? ->
        VideoTexturePlayer(appContext, pixelFormat ?: "nv12")
          .also { players.add(it) }
      }

      Property("currentTimeSec") { player: VideoTexturePlayer ->
        player.currentTimeSec
      }

      Property("frameSourceKey") { player: VideoTexturePlayer ->
        player.frameSourceKey
      }

      Property("volume") { player: VideoTexturePlayer ->
        player.volume
      }
      .set { player: VideoTexturePlayer, volume: Double ->
        player.setVolume(volume)
      }

      Function("installFrameSource") { player: VideoTexturePlayer ->
        val reactContext = appContext.reactContext as? com.facebook.react.bridge.ReactContext
          ?: throw IllegalStateException("VideoTexture: React context unavailable")
        val jsContext = reactContext.javaScriptContextHolder
          ?: throw IllegalStateException("VideoTexture: JS context holder unavailable")
        // The pointer is only valid while the holder's monitor is held.
        synchronized(jsContext) {
          val runtimePtr = jsContext.get().takeIf { it != 0L }
            ?: throw IllegalStateException("VideoTexture: JS runtime pointer unavailable")
          player.installFrameSource(runtimePtr)
        }
      }

      Function("loadClip") { player: VideoTexturePlayer, options: LoadClipOptions ->
        player.loadClip(options)
      }

      Function("setPaused") { player: VideoTexturePlayer, paused: Boolean ->
        player.setPaused(paused)
      }

      Function("setRate") { player: VideoTexturePlayer, rate: Double ->
        player.setRate(rate)
      }

      Function("rampRate") { player: VideoTexturePlayer, rate: Double, durationMs: Double ->
        player.rampRate(rate, durationMs)
      }

      Function("seek") { player: VideoTexturePlayer, sec: Double ->
        player.seek(sec)
      }
    }
  }
}
