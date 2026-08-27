/**
 * The module's single platform seam (native variant — see `nativeModule.web.ts`).
 *
 * Everything that can touch the `VideoTexture` native module goes through here, and
 * nothing here runs at import time. `requireNativeModule` throws when the module is
 * missing, so calling it at module scope would take down the whole bundle during
 * evaluation instead of failing at the point of use — hence the lazy
 * `requireOptionalNativeModule` below.
 */
import { requireOptionalNativeModule } from 'expo-modules-core';
import type {
    NativeVideoFormatSupport,
    VideoFormatQuery,
    VideoTextureNativeModule,
    VideoTextureNativeModuleAccess,
} from './types';

/** Appended to the errors thrown by the module's entry points. */
export const unavailableReason =
    'the VideoTexture native module is not linked in this build (rebuild the dev client / run a prebuild)';

let cached: VideoTextureNativeModule | null | undefined;

/** `null` when the native module is not linked. Result is memoised. */
export function getVideoTextureModule(): VideoTextureNativeModule | null {
    cached ??= requireOptionalNativeModule<VideoTextureNativeModule>('VideoTexture');
    return cached;
}

/**
 * Whether video textures can actually be used here. A function rather than a const
 * because answering it requires reaching for the native module, which must not
 * happen at import time.
 */
export function isVideoTextureSupported(): boolean {
    return getVideoTextureModule() !== null;
}

const failed = (
    formats: VideoFormatQuery[],
    kind: 'platform-error' | 'module-unavailable',
    message: string,
): NativeVideoFormatSupport[] =>
    formats.map(() => ({
        supported: false,
        hardwareAccelerated: false,
        sustainedRate: false,
        error: { kind, message },
    }));

export async function queryNativeVideoFormatSupport(
    formats: VideoFormatQuery[],
): Promise<NativeVideoFormatSupport[]> {
    const module = getVideoTextureModule();
    if (!module) return failed(formats, 'module-unavailable', unavailableReason);

    try {
        const results = await module.queryVideoFormatSupport(formats);
        if (results.length !== formats.length) {
            throw new Error(
                `native module returned ${results.length} results for ${formats.length} formats`,
            );
        }
        return results;
    } catch (caught: unknown) {
        const message = caught instanceof Error ? caught.message : String(caught);
        return failed(formats, 'platform-error', message);
    }
}

/** Compile-time proof this variant still matches the seam contract. */
export const ACCESS_CONTRACT: VideoTextureNativeModuleAccess = {
    unavailableReason,
    getVideoTextureModule,
    isVideoTextureSupported,
    queryNativeVideoFormatSupport,
};
