# Android implementation

Mirrors `ios/` behavior with Android-native plumbing. Same TS contract (`src/VideoPlayer.ts`).

- **Decoder/transport** — `VideoSource.kt`: one ExoPlayer (media3) on the main looper renders
  video into `GlVideoBridge`'s `SurfaceTexture`; the bridge blits each frame to RGBA on the GPU
  into an `ImageReader` (`RGBA_8888`, `USAGE_GPU_SAMPLED_IMAGE`, pool of 5), so each frame
  arrives as a plain RGBA8 `AHardwareBuffer`. Push model (`onImageAvailable` → JNI), no polling.
  `setMediaItem(item, startPositionMs)` is the authoritative generation-bound clip start
  (subsumes iOS's deferred-seek + preroll). Each `Image` stays acquired until the consumer
  calls `releaseFrame` after its GPU work completes, preventing the decoder from rewriting a
  buffer while WebGPU is still using it.
- **GL bridge** — `GlVideoBridge.kt`: decoders hand out vendor-specific YUV gralloc layouts
  (Exynos SBWC/SP_M, Qualcomm UBWC, Tensor SPN) whose Vulkan import is driver roulette —
  Samsung Xclipse cannot sample their chroma planes through that path at all (S22 renders
  green, S24 teal; see `patches/dawn/README.md` for the full investigation). The one path every
  vendor certifies is GL's external texture, so the bridge samples the decoder frame there and
  writes RGBA. Cost: one fullscreen quad per frame (<1 ms GPU, no CPU copies). RGBA8 buffers
  import through Dawn's ordinary color path on every GPU — no external formats, no YCbCr.
- **Frame source** — shared `../cpp/FrameSourceHostObject.{h,cpp}` (same JSI HostObject as iOS)
  bound to `AndroidFrameProvider` (`src/main/cpp/`): a mutex-guarded latest-AHardwareBuffer
  latch. Worklet acquires never cross JNI; Kotlin pushes via
  `FrameSourceNative` (plain JNI, `VideoTextureJNI.cpp`). Handles flow to JS as bigints for
  `RNWebGPU.createVideoFrameFromNativeBuffer()`.
- **Seamless loop** — `loopMode: 'loop'` loads TWO identical `MediaItem`s +
  `REPEAT_MODE_ALL`: ExoPlayer prewarms the next playlist period, so the wrap is gapless
  (`REPEAT_MODE_ONE` resets the renderer and can hitch). `startSec` applies to the first
  cycle only — the loop always wraps to 0, matching the baked file's frame(last)→frame(0)
  seam. `currentPosition` is per-item, so reported time is already the 0→L sawtooth;
  `'ended'` never fires.
- **Requirements** — a physical device, API 29+ (`ImageReader` usage-flags overload).
  `frameSource.pixelFormat` reports `'bgra8'` (frames are RGBA after the bridge); `'nv12'` is
  accepted at construction as the legacy request alias. Rotated video is rejected at load.
- **WebGPU side** — samples arrive as RGB on both platforms (Android converts in the GL
  bridge; iOS converts in Dawn's Metal sampler), so shaders apply **no** colour conversion.
  `GPUExternalTexture.yuvToRgbMatrix` is still exposed and is the identity for every frame this
  module produces — consumers can assert on it as a divergence check.
- **Bind group 0 is reserved for the video** — put the `texture_external` and its sampler
  there and nothing else; every other resource goes in group 1. Tint expands
  `texture_external` into several internal bindings packed into that same descriptor set;
  resources sharing it can read back as zero. This can be confirmed with a static texture that
  no render pass writes: the failure follows the shared descriptor set rather than the contents.
- **No emulator support** — kept from the direct-import era, when gfxstream could not
  Vulkan-import the codec's YUV gralloc buffers. The RGBA bridge may well work there now, but
  it has not been validated, so `VideoTexturePlayer` still refuses to construct on
  ranchu/goldfish with a clear message.
