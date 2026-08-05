/**
 * Importing this module is safe on every platform, including web. Only the entry
 * points that genuinely need hardware video decode (`createVideoTexturePlayer`,
 * `useVideoTexture`) fail where it is unavailable, and they throw when *called* —
 * check `isVideoTextureSupported()` on paths that can run on web.
 */
export { VIDEO_TEXTURE_DEVICE_FEATURES } from './deviceFeatures';
export { isVideoTextureSupported } from './nativeModule';
export { useVideoTexture, type VideoTexture } from './useVideoTexture';
export { createVideoTexturePlayer, getFrameSource } from './VideoPlayer';
export type {
    LoadClipOptions,
    NativeFrameSource,
    NativeVideoSnapshot,
    NativeVideoStatus,
    VideoLoopMode,
    VideoPixelFormat,
    VideoPlayerStatus,
    VideoTexturePlayer,
} from './types';
