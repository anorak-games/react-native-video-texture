import { afterEach, describe, expect, it, vi } from 'vitest';
import { getFrameSource } from './VideoPlayer';
import type { NativeFrameSource, VideoTexturePlayer } from './types';

vi.mock('./nativeModule', () => ({
    getVideoTextureModule: () => null,
    unavailableReason: 'unavailable in tests',
}));

const registryName = '__videoTextureFrameSources';

function createFrameSource(): NativeFrameSource {
    return {
        poll: () => ({
            frame: null,
            uri: null,
            generation: 0,
            status: 'idle',
            statusSeq: 0,
            errorSeq: 0,
            errorMessage: null,
            durationSec: 0,
            actualRate: 0,
        }),
        loadClip: vi.fn(),
        setPaused: vi.fn(),
        setRate: vi.fn(),
        rampRate: vi.fn(),
        setVolume: vi.fn(),
        releaseFrame: vi.fn(),
        releaseAll: vi.fn(),
        pixelFormat: 'nv12',
    };
}

afterEach(() => {
    delete (globalThis as Record<string, unknown>)[registryName];
});

describe('getFrameSource', () => {
    it('consumes the registry entry and reuses the source for the same player', () => {
        const registry: Record<string, NativeFrameSource> = {};
        const source = createFrameSource();
        const installFrameSource = vi.fn(() => {
            registry.player1 = source;
        });
        const player = {
            frameSourceKey: 'player1',
            installFrameSource,
        } as unknown as VideoTexturePlayer;
        (globalThis as Record<string, unknown>)[registryName] = registry;

        expect(getFrameSource(player)).toBe(source);
        expect(registry).not.toHaveProperty('player1');
        expect(getFrameSource(player)).toBe(source);
        expect(installFrameSource).toHaveBeenCalledOnce();
    });

    it('fails when native installation does not provide a frame source', () => {
        const player = {
            frameSourceKey: 'player2',
            installFrameSource: vi.fn(),
        } as unknown as VideoTexturePlayer;

        expect(() => getFrameSource(player)).toThrow(
            'VideoTexture frame source missing for player2',
        );
    });
});
