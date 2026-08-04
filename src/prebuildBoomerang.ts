import { getVideoTextureModule } from './nativeModule';

/**
 * Pre-builds (and caches to tmp/) the reversed composition required for boomerang
 * looping on the given clip URI. Call this after the clip file is cached locally so
 * the first actual play finds the reversed file ready — eliminating the 1-3 second
 * `renderReversed` stall that would otherwise fire mid-playback on first play.
 *
 * iOS and Android; resolves immediately with `false` (skipped/failed) anywhere video
 * textures are unavailable, so callers on any platform can fire this unconditionally.
 * Safe to call concurrently or repeatedly — the native side is idempotent (cached
 * reversed file is reused, duplicate calls for the same URI are no-ops).
 */
export async function prebuildBoomerang(uri: string): Promise<boolean> {
    const nativeModule = getVideoTextureModule();
    if (typeof nativeModule?.prebuildBoomerang !== 'function') {
        return false;
    }
    try {
        return await nativeModule.prebuildBoomerang(uri);
    } catch {
        return false;
    }
}
