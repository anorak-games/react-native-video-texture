import { afterEach, describe, expect, it, vi } from 'vitest';

async function featuresFor(platform: string): Promise<readonly GPUFeatureName[]> {
    vi.resetModules();
    vi.doMock('expo-modules-core', () => ({ Platform: { OS: platform } }));
    return (await import('./deviceFeatures')).VIDEO_TEXTURE_DEVICE_FEATURES;
}

afterEach(() => {
    vi.doUnmock('expo-modules-core');
});

describe('VIDEO_TEXTURE_DEVICE_FEATURES', () => {
    it('requests only the IOSurface multiplanar feature on iOS', async () => {
        await expect(featuresFor('ios')).resolves.toEqual(['dawn-multi-planar-formats']);
    });

    it('requests only the opaque YCbCr import features on Android', async () => {
        await expect(featuresFor('android')).resolves.toEqual([
            'ycbcr-vulkan-samplers',
            'opaque-ycbcr-android-for-external-texture',
        ]);
    });

    it('does not request native video texture features on other platforms', async () => {
        await expect(featuresFor('web')).resolves.toEqual([]);
    });
});
