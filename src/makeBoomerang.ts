import { getVideoTextureModule, unavailableReason } from './nativeModule';

/**
 * Renders a boomerang (forward-then-reverse) copy of `inputUri` to `outputPath`,
 * simulating the eventual server-side pre-bake. The output video is
 * `[forward 0..N-1][reverse N-2..1]` — both duplicate endpoint frames dropped, so
 * neither the turnaround nor the loop seam holds a frame and the file loops
 * seamlessly with `loopMode: 'loop'`. Audio is the source's forward audio twice,
 * trimmed to the video length.
 *
 * The caller owns the destination: an existing file at `outputPath` is
 * overwritten. This is a one-shot offline encode (≈2× decode + 1× encode of the
 * clip) — run it during a loading phase, not during playback.
 *
 * Resolves with `outputPath`; rejects on any failure (bad input, no video track,
 * encoder errors). Throws where video textures are unavailable — check
 * `isVideoTextureSupported()` on paths that can run there.
 */
export async function makeBoomerang(inputUri: string, outputPath: string): Promise<string> {
    const nativeModule = getVideoTextureModule();
    if (!nativeModule) {
        throw new Error(`Cannot make a boomerang: ${unavailableReason}`);
    }
    if (typeof nativeModule.makeBoomerang !== 'function') {
        throw new Error(
            'Cannot make a boomerang: the linked VideoTexture native module predates makeBoomerang (rebuild the dev client)',
        );
    }
    return nativeModule.makeBoomerang(inputUri, outputPath);
}
