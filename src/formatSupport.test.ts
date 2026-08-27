import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { NativeVideoFormatSupport, VideoFormatQuery } from './types';

const queryNativeVideoFormatSupport = vi.hoisted(() => vi.fn());

vi.mock('./nativeModule', () => ({ queryNativeVideoFormatSupport }));

import { queryVideoFormatSupport } from './formatSupport';

const avc: VideoFormatQuery = {
    codec: 'avc1.64002A',
    width: 1920,
    height: 1080,
    fps: 24,
};

const hevc: VideoFormatQuery = {
    codec: 'hvc1.1.6.L153',
    width: 3840,
    height: 2160,
    fps: 24,
};

const supported: NativeVideoFormatSupport = {
    supported: true,
    hardwareAccelerated: true,
    sustainedRate: true,
    error: null,
};

beforeEach(() => {
    queryNativeVideoFormatSupport.mockReset();
});

describe('queryVideoFormatSupport', () => {
    it('isolates invalid candidates and preserves result order', async () => {
        queryNativeVideoFormatSupport.mockResolvedValue([
            supported,
            {
                supported: false,
                hardwareAccelerated: false,
                sustainedRate: false,
                error: null,
            },
        ]);
        const malformed = { ...avc, codec: 'not-a-codec' };

        const results = await queryVideoFormatSupport([avc, malformed, hevc]);

        expect(queryNativeVideoFormatSupport).toHaveBeenCalledWith([avc, hevc]);
        expect(results).toEqual([
            { ...avc, ...supported },
            {
                ...malformed,
                supported: false,
                hardwareAccelerated: false,
                sustainedRate: false,
                error: {
                    kind: 'invalid-candidate',
                    message: 'Unsupported or malformed codec string: not-a-codec',
                },
            },
            {
                ...hevc,
                supported: false,
                hardwareAccelerated: false,
                sustainedRate: false,
                error: null,
            },
        ]);
    });

    it('preserves a per-candidate platform error without affecting later results', async () => {
        queryNativeVideoFormatSupport.mockResolvedValue([
            supported,
            {
                supported: false,
                hardwareAccelerated: false,
                sustainedRate: false,
                error: { kind: 'platform-error', message: 'codec service failed' },
            },
            supported,
        ]);
        const currentAvc = { ...avc, codec: 'avc1.640033', width: 3840, height: 2160 };

        const results = await queryVideoFormatSupport([currentAvc, hevc, avc]);

        expect(results[0]).toEqual({ ...currentAvc, ...supported });
        expect(results[1].error).toEqual({
            kind: 'platform-error',
            message: 'codec service failed',
        });
        expect(results[2]).toEqual({ ...avc, ...supported });
    });

    it('converts a complete native failure without replacing invalid candidate errors', async () => {
        queryNativeVideoFormatSupport.mockRejectedValue(new Error('bridge failed'));
        const malformed = { ...avc, codec: 'not-a-codec' };

        const results = await queryVideoFormatSupport([avc, malformed, hevc]);

        expect(results.map((result) => result.error)).toEqual([
            { kind: 'platform-error', message: 'bridge failed' },
            {
                kind: 'invalid-candidate',
                message: 'Unsupported or malformed codec string: not-a-codec',
            },
            { kind: 'platform-error', message: 'bridge failed' },
        ]);
    });

    it('validates dimensions and frame rate independently', async () => {
        const invalidWidth = { ...avc, width: 0 };
        const invalidHeight = { ...avc, height: 1080.5 };
        const invalidFps = { ...avc, fps: Number.NaN };

        const results = await queryVideoFormatSupport([
            invalidWidth,
            invalidHeight,
            invalidFps,
        ]);

        expect(results.map((result) => result.error?.kind)).toEqual([
            'invalid-candidate',
            'invalid-candidate',
            'invalid-candidate',
        ]);
        expect(queryNativeVideoFormatSupport).not.toHaveBeenCalled();
    });

    it('returns an empty result without calling native', async () => {
        await expect(queryVideoFormatSupport([])).resolves.toEqual([]);
        expect(queryNativeVideoFormatSupport).not.toHaveBeenCalled();
    });
});
