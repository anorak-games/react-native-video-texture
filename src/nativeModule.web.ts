/**
 * The module's single platform seam (web variant — see `nativeModule.ts`).
 *
 * Metro resolves `.web.ts` ahead of `.ts` when bundling for web, so on web this file
 * replaces the native seam and nothing ever asks for the `VideoTexture` native module.
 * There is no web implementation of hardware video decode into
 * a WebGPU-importable buffer and there is not meant to be one: the point of this file
 * is that *importing* the module is free on web, so an app can boot and render
 * everything else. The entry points that genuinely need native throw when called.
 */
import type {
    NativeVideoFormatSupport,
    VideoFormatQuery,
    VideoTextureNativeModule,
    VideoTextureNativeModuleAccess,
} from './types';

/** Appended to the errors thrown by the module's entry points. */
export const unavailableReason = 'video textures are not supported on web';

/** Always `null` on web. */
export function getVideoTextureModule(): VideoTextureNativeModule | null {
    return null;
}

export function isVideoTextureSupported(): boolean {
    return false;
}

export async function queryNativeVideoFormatSupport(
    formats: VideoFormatQuery[],
): Promise<NativeVideoFormatSupport[]> {
    return formats.map(() => ({
        supported: false,
        hardwareAccelerated: false,
        sustainedRate: false,
        error: null,
    }));
}

/** Compile-time proof this variant still matches the seam contract. */
export const ACCESS_CONTRACT: VideoTextureNativeModuleAccess = {
    unavailableReason,
    getVideoTextureModule,
    isVideoTextureSupported,
    queryNativeVideoFormatSupport,
};
