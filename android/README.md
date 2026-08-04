# Android implementation

Mirrors `ios/` behavior with Android-native plumbing. Same TS contract (`src/VideoPlayer.ts`).

- **Decoder/transport** — `VideoSource.kt`: one ExoPlayer (media3) on the main looper renders
  video into an `ImageReader` (`PRIVATE`, `USAGE_GPU_SAMPLED_IMAGE`, pool of 5), so each decoded
  frame arrives as an `AHardwareBuffer`. Push model (`onImageAvailable` → JNI), no polling.
  `setMediaItem(item, startPositionMs)` is the authoritative generation-bound clip start
  (subsumes iOS's deferred-seek + preroll). The last two `Image`s stay open as a
  producer-rewrite guard; the C++ +1 ref covers memory lifetime.
- **Frame source** — shared `../cpp/FrameSourceHostObject.{h,cpp}` (same JSI HostObject as iOS)
  bound to `AndroidFrameProvider` (`src/main/cpp/`): a mutex-guarded latest-AHardwareBuffer
  latch. Worklet acquires never cross JNI; Kotlin pushes via
  `FrameSourceNative` (plain JNI, `VideoTextureJNI.cpp`). Handles flow to JS as bigints for
  `RNWebGPU.createVideoFrameFromNativeBuffer()`.
- **Boomerang** — `BoomerangComposition.kt`: reversed file pre-rendered GOP-by-GOP with
  MediaCodec (frames spooled to a cache raw file, ~2 frames of RAM regardless of GOP size),
  forward audio muxed in compressed. Playback is a two-item playlist `[original, reversed]` +
  `REPEAT_MODE_ALL` — video fwd/rev/fwd…, audio always forward. `currentTimeSec` reports true
  forward media time (item 0 = position; item 1 = L − position). Cached in `cacheDir` keyed by
  URI hash; `prebuildBoomerang` warms it. The reversed leg renders at **source resolution** —
  this keeps image quality consistent at every turnaround. Peak RAM is unaffected (~2 frames),
  but peak *disk* is one GOP of raw I420
  (≈12 MB/frame at 2160p), released when the render finishes. The cache filename carries a
  format version (`boomerang-rev-v2-…`); bump it when the output format changes, or devices
  keep serving stale files.
- **Requirements** — a physical device, API 29+ (`ImageReader` usage-flags overload);
  `pixelFormat` must be `'nv12'` (Android decoders yield YCbCr AHardwareBuffers; no BGRA
  path). Rotated video is rejected at load.
- **WebGPU side** — the app must request `dawn-multi-planar-formats`, `ycbcr-vulkan-samplers`,
  and `opaque-ycbcr-android-for-external-texture` in `requestDevice`, and its shader must apply
  `GPUExternalTexture.yuvToRgbMatrix` after sampling. Dawn imports YUV AHardwareBuffers as
  `OpaqueYCbCrAndroid` and samples them through a Vulkan conversion hard-coded to
  `RGB_IDENTITY`, so the sample arrives as raw `[Y, Cb, Cr]` and the model conversion is the
  consumer's job; react-native-webgpu derives the matrix from the driver's suggested model
  and range per buffer and exposes it on the imported texture. It is the identity passthrough
  on iOS, so a shader can multiply unconditionally.
- **Bind group 0 is reserved for the video** — put the `texture_external` and its sampler
  there and nothing else; every other resource goes in group 1. Dawn imports the frame as an
  opaque YCbCr image sampled through an immutable-conversion sampler, and Tint expands
  `texture_external` into several internal bindings packed into that same descriptor set;
  resources sharing it can read back as zero. This can be confirmed with a static texture that
  no render pass writes: the failure follows the shared descriptor set rather than the contents.
- **No emulator support** — the emulator (gfxstream) can neither Vulkan-import the codec's
  YUV gralloc buffers (tight NV12 allocation vs padded host requirement) nor report a usable
  AHardwareBuffer `allocationSize`, so Dawn's shared-texture-memory validation rejects every
  frame. `VideoTexturePlayer` refuses to construct on ranchu/goldfish with a clear message.
