import { Platform } from 'expo-modules-core';

const FEATURES_BY_PLATFORM: Record<string, readonly string[]> = {
    ios: ['dawn-multi-planar-formats'],
    android: [
        'dawn-multi-planar-formats',
        'ycbcr-vulkan-samplers',
        'opaque-ycbcr-android-for-external-texture',
    ],
};

/**
 * Dawn feature names the WebGPU device MUST request for decoded video frames to
 * import as external textures, for the current platform (empty where unsupported).
 * Spread into your GPUDeviceDescriptor.requiredFeatures alongside your own. Typed as
 * GPUFeatureName for drop-in use, though these are Dawn-specific names outside the
 * standard union — hence the cast is done here so consumers don't have to.
 */
export const VIDEO_TEXTURE_DEVICE_FEATURES = (FEATURES_BY_PLATFORM[Platform.OS] ??
    []) as unknown as readonly GPUFeatureName[];
