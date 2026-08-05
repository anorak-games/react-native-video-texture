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
 * - `'off'` (default): the clip region plays once and settles at status `'ended'` — at
 *   `endSec` when one is set, otherwise at the end of the file.
 * - `'loop'`: native loops the clip region seamlessly (no wrap-around hitch). Without an
 *   `endSec` the whole file loops and every wrap restarts at 0; with a region the wrap is
 *   frame(endSec−1)→frame(startSec). Status `'ended'` never fires.
 */
export type VideoLoopMode = 'off' | 'loop';

export type VideoPixelFormat = 'bgra8' | 'nv12';

export interface LoadClipOptions {
    uri: string;
    /** Authoritative clip-start second, applied once per generation. */
    startSec?: number;
    /**
     * Absolute file time (seconds) bounding the playable region. `'off'`: status `'ended'`
     * settles here instead of at file end. `'loop'`: the [startSec, endSec] region loops
     * seamlessly. Omitted / <= 0 = no bound (whole file). Timestamps stay ABSOLUTE file
     * time in both modes — `ptsSec` sawtooths startSec→endSec under a region loop.
     */
    endSec?: number;
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
     * RNWebGPU.createVideoFrameFromNativeBuffer() accepts. Call releaseFrame(handle)
     * only after all GPU work using this frame has completed.
     */
    handle: bigint;
    /**
     * Media time of THIS frame, in seconds — always ABSOLUTE file time. Negative when
     * unknown. In `'loop'` mode it sawtooths startSec→endSec (0→duration without a
     * region), one rise per cycle.
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
