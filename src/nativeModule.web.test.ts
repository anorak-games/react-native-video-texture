import { describe, expect, it } from 'vitest';
import { queryNativeVideoFormatSupport } from './nativeModule.web';

describe('queryNativeVideoFormatSupport on web', () => {
    it('reports every valid format as unsupported without an error', async () => {
        await expect(
            queryNativeVideoFormatSupport([
                { codec: 'avc1.64002A', width: 1920, height: 1080, fps: 24 },
                { codec: 'hvc1.1.6.L153', width: 3840, height: 2160, fps: 24 },
            ]),
        ).resolves.toEqual([
            {
                supported: false,
                hardwareAccelerated: false,
                sustainedRate: false,
                error: null,
            },
            {
                supported: false,
                hardwareAccelerated: false,
                sustainedRate: false,
                error: null,
            },
        ]);
    });
});
