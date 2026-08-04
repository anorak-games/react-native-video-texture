/**
 * React lifecycle for a video-texture player: creates the headless player and
 * its worklet-capturable frame source once, releases the player on unmount.
 * Transport (loadClip/pause/seek/…) and rendering stay with the consumer —
 * capture `frameSource` in a worklet, wrap acquired handles with
 * RNWebGPU.createVideoFrameFromNativeBuffer, and importExternalTexture per tick.
 *
 * Throws on first render where video textures are unavailable (web, or a build without
 * the native module linked) — gate the rendering path on `isVideoTextureSupported()`
 * rather than calling this hook conditionally.
 */
import { useReleasingSharedObject } from 'expo-modules-core';
import { useMemo } from 'react';
import { createVideoTexturePlayer, getFrameSource } from './VideoPlayer';
import type { NativeFrameSource, VideoPixelFormat, VideoTexturePlayer } from './types';

export interface VideoTexture {
    player: VideoTexturePlayer;
    frameSource: NativeFrameSource;
}

export function useVideoTexture(options?: { pixelFormat?: VideoPixelFormat }): VideoTexture {
    const pixelFormat = options?.pixelFormat ?? 'nv12';
    const player = useReleasingSharedObject(
        () => createVideoTexturePlayer(pixelFormat),
        [pixelFormat],
    );
    const frameSource = useMemo(() => getFrameSource(player), [player]);

    return useMemo(() => ({ player, frameSource }), [player, frameSource]);
}
