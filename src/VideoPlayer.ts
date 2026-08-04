/**
 * Headless video player vending decoded frames as WebGPU-importable native
 * buffers. Platform-neutral contract: iOS implements it via VideoTexturePlayer
 * (AVQueuePlayer transport); Android implements the same shape
 * (MediaCodec/ExoPlayer → AHardwareBuffer) under android/.
 *
 * This file is platform-neutral itself — all native access goes through the
 * `nativeModule` seam, which is what keeps it importable on web.
 */
import { getVideoTextureModule, unavailableReason } from './nativeModule';
import type { NativeFrameSource, VideoPixelFormat, VideoTexturePlayer } from './types';

const frameSources = new WeakMap<VideoTexturePlayer, NativeFrameSource>();

/**
 * Creates a headless player. Throws where video textures are unavailable (web, or a
 * build without the native module linked) — check `isVideoTextureSupported()` first
 * if the calling path can run there.
 */
export function createVideoTexturePlayer(
    pixelFormat: VideoPixelFormat = 'nv12',
): VideoTexturePlayer {
    const nativeModule = getVideoTextureModule();
    if (!nativeModule) {
        throw new Error(`Cannot create a video texture player: ${unavailableReason}`);
    }
    return new nativeModule.VideoTexturePlayer(pixelFormat);
}

/**
 * Install (idempotent) and fetch the player's frame source. The returned
 * HostObject is worklet-serializable — capture it in the render worklet.
 */
export function getFrameSource(player: VideoTexturePlayer): NativeFrameSource {
    const cached = frameSources.get(player);
    if (cached) {
        return cached;
    }

    player.installFrameSource();
    const registry = (globalThis as Record<string, unknown>).__videoTextureFrameSources as
        | Record<string, NativeFrameSource>
        | undefined;
    const source = registry?.[player.frameSourceKey];
    if (!source) {
        throw new Error(`VideoTexture frame source missing for ${player.frameSourceKey}`);
    }

    delete registry[player.frameSourceKey];
    frameSources.set(player, source);
    return source;
}
