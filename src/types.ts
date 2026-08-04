import { SharedObject } from 'expo-modules-core';

/**
 * The module's whole public contract, in one platform-neutral file.
 *
 * Nothing here may import a platform implementation: `nativeModule.ts` and
 * `nativeModule.web.ts` are both written against these types, so keeping them
 * here is what makes the two variants impossible to drift apart.
 */

export type VideoPlayerStatus = 'idle' | 'loading' | 'ready' | 'playing' | 'ended' | 'error';

/**
 * Transport loop mode.
 * - `'off'` (default): the clip plays once and settles at status `'ended'`.
 * - `'boomerang'`: native plays the clip forward→reverse→forward seamlessly (video
 *   boomerangs, audio keeps playing forward and loops).
 */
export type VideoLoopMode = 'off' | 'boomerang';

export type VideoPixelFormat = 'bgra8' | 'nv12';

export interface LoadClipOptions {
    uri: string;
    /** Authoritative clip-start second, applied once per generation. */
    startSec?: number;
    /** Bump to re-arm the clip start on an otherwise-identical load. */
    generation: number;
    loopMode?: VideoLoopMode;
    autoPlay?: boolean;
}

/**
 * A decoded frame and the metadata describing it.
 *
 * The timestamp travels WITH the pixels on purpose. When media time arrived by a separate
 * native -> JS -> worklet path it raced the frames: a consumer could sample world state for
 * one moment while drawing a different moment's image, and which one won depended on thread
 * scheduling. Reading both from one call makes that race structurally impossible.
 */
export interface VideoFrame {
    /**
     * +1-retained CVPixelBufferRef (iOS) / AHardwareBuffer* (Android) — exactly what
     * RNWebGPU.createVideoFrameFromNativeBuffer() accepts. Since the wrap retains
     * internally, call releaseFrame(handle) right after wrapping.
     */
    handle: bigint;
    /**
     * Media time of THIS frame, in seconds. It moves backwards during boomerang reverse
     * playback and is negative when unknown.
     *
     * Exact on iOS (the item time the buffer was fetched for). On Android it is the 60Hz
     * player-position snapshot extrapolated by wall clock, so up to ~16ms stale: ExoPlayer
     * is app-thread-only and frames are deposited from the ImageReader thread.
     */
    ptsSec: number;
    /** Clip generation this frame belongs to; bumps on every loadClip. */
    generation: number;
}

export type NativeVideoStatus = 'idle' | 'loading' | 'playing' | 'paused' | 'ended' | 'error';

export interface NativeVideoSnapshot {
    frame: VideoFrame | null;
    uri: string | null;
    generation: number;
    status: NativeVideoStatus;
    statusSeq: number;
    errorSeq: number;
    errorMessage: string | null;
    durationSec: number;
    actualRate: number;
}

/**
 * jsi::HostObject vending decoded frames to a render worklet.
 */
export interface NativeFrameSource {
    /** Latest transport state and at most one newly decoded frame. Never blocks. */
    poll(): NativeVideoSnapshot;
    loadClip(options: LoadClipOptions): void;
    setPaused(paused: boolean): void;
    setRate(rate: number): void;
    rampRate(rate: number, durationMs: number): void;
    setVolume(volume: number): void;
    releaseFrame(handle: bigint): void;
    releaseAll(): void;
    readonly pixelFormat: VideoPixelFormat;
}

export declare class VideoTexturePlayer extends SharedObject {
    constructor(pixelFormat?: VideoPixelFormat);
    readonly currentTimeSec: number;
    readonly frameSourceKey: string;
    volume: number;
    installFrameSource(): void;
    loadClip(options: LoadClipOptions): void;
    setPaused(paused: boolean): void;
    setRate(rate: number): void;
    rampRate(rate: number, durationMs: number): void;
    seek(sec: number): void;
}

/** Shape of the `VideoTexture` Expo module as exposed to JS. */
export interface VideoTextureNativeModule {
    readonly VideoTexturePlayer: typeof VideoTexturePlayer;
    /** Absent on older native builds — always feature-check before calling. */
    prebuildBoomerang?: (uri: string) => Promise<boolean>;
}

/**
 * The platform seam every implementation of `nativeModule` must satisfy. Applied
 * with `satisfies` in both variants so a missing or mistyped export fails
 * `npm run typecheck` rather than the web bundle.
 */
export interface VideoTextureNativeModuleAccess {
    unavailableReason: string;
    getVideoTextureModule: () => VideoTextureNativeModule | null;
    isVideoTextureSupported: () => boolean;
}
