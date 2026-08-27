import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { VideoFormatQuery, VideoTextureNativeModule } from './types';

const requireOptionalNativeModule = vi.hoisted(() => vi.fn());

vi.mock('expo-modules-core', () => ({ requireOptionalNativeModule }));

import { queryNativeVideoFormatSupport, unavailableReason } from './nativeModule';

const formats: VideoFormatQuery[] = [
    { codec: 'avc1.64002A', width: 1920, height: 1080, fps: 24 },
    { codec: 'hvc1.1.6.L153', width: 3840, height: 2160, fps: 24 },
];

beforeEach(() => {
    requireOptionalNativeModule.mockReset();
});

describe('queryNativeVideoFormatSupport', () => {
    it('reports a missing native module per candidate', async () => {
        requireOptionalNativeModule.mockReturnValue(null);

        const results = await queryNativeVideoFormatSupport(formats);

        expect(results).toHaveLength(formats.length);
        expect(results.every((result) => result.error?.kind === 'module-unavailable')).toBe(
            true,
        );
        expect(results[0].error?.message).toBe(unavailableReason);
    });

    it('converts a complete native call failure per candidate', async () => {
        requireOptionalNativeModule.mockReturnValue({
            queryVideoFormatSupport: vi.fn().mockRejectedValue(new Error('bridge failed')),
        } as unknown as VideoTextureNativeModule);

        const results = await queryNativeVideoFormatSupport(formats);

        expect(results).toEqual(
            formats.map(() => ({
                supported: false,
                hardwareAccelerated: false,
                sustainedRate: false,
                error: { kind: 'platform-error', message: 'bridge failed' },
            })),
        );
    });
});
