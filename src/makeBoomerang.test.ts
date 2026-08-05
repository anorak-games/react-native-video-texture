import { afterEach, describe, expect, it, vi } from 'vitest';
import { makeBoomerang } from './makeBoomerang';
import type { VideoTextureNativeModule } from './types';

let nativeModule: VideoTextureNativeModule | null = null;

vi.mock('./nativeModule', () => ({
    getVideoTextureModule: () => nativeModule,
    unavailableReason: 'unavailable in tests',
}));

afterEach(() => {
    nativeModule = null;
});

describe('makeBoomerang', () => {
    it('passes through to the native function and resolves its result', async () => {
        const nativeMakeBoomerang = vi.fn(async (_input: string, outputPath: string) => outputPath);
        nativeModule = { makeBoomerang: nativeMakeBoomerang } as unknown as VideoTextureNativeModule;

        await expect(makeBoomerang('file:///in.mp4', '/out/boom.mp4')).resolves.toBe(
            '/out/boom.mp4',
        );
        expect(nativeMakeBoomerang).toHaveBeenCalledWith('file:///in.mp4', '/out/boom.mp4');
    });

    it('propagates a native rejection instead of swallowing it', async () => {
        nativeModule = {
            makeBoomerang: vi.fn(async () => {
                throw new Error('no video track');
            }),
        } as unknown as VideoTextureNativeModule;

        await expect(makeBoomerang('file:///in.mp4', '/out/boom.mp4')).rejects.toThrow(
            'no video track',
        );
    });

    it('throws when the native module is absent', async () => {
        await expect(makeBoomerang('file:///in.mp4', '/out/boom.mp4')).rejects.toThrow(
            'unavailable in tests',
        );
    });

    it('throws when the linked native build predates makeBoomerang', async () => {
        nativeModule = {} as unknown as VideoTextureNativeModule;

        await expect(makeBoomerang('file:///in.mp4', '/out/boom.mp4')).rejects.toThrow(
            'predates makeBoomerang',
        );
    });
});
