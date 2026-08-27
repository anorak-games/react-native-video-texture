import { queryNativeVideoFormatSupport } from './nativeModule';
import type {
    NativeVideoFormatSupport,
    VideoFormatQuery,
    VideoFormatSupport,
} from './types';

const AVC_CODEC = /^avc1\.[0-9a-f]{6}$/i;
const HEVC_CODEC = /^hvc1\.[0-9]+\.[0-9a-f]+\.[LH][0-9]+(?:\.[0-9a-f]{2}){0,6}$/i;

function invalidCandidate(format: VideoFormatQuery, message: string): VideoFormatSupport {
    return {
        ...format,
        supported: false,
        hardwareAccelerated: false,
        sustainedRate: false,
        error: { kind: 'invalid-candidate', message },
    };
}

function validate(format: VideoFormatQuery): string | null {
    if (!AVC_CODEC.test(format.codec) && !HEVC_CODEC.test(format.codec)) {
        return `Unsupported or malformed codec string: ${format.codec}`;
    }
    if (!Number.isSafeInteger(format.width) || format.width <= 0) {
        return `width must be a positive integer, got ${format.width}`;
    }
    if (!Number.isSafeInteger(format.height) || format.height <= 0) {
        return `height must be a positive integer, got ${format.height}`;
    }
    if (!Number.isFinite(format.fps) || format.fps <= 0) {
        return `fps must be a positive finite number, got ${format.fps}`;
    }
    return null;
}

function combine(
    format: VideoFormatQuery,
    support: NativeVideoFormatSupport,
): VideoFormatSupport {
    return { ...format, ...support };
}

function platformFailure(format: VideoFormatQuery, message: string): VideoFormatSupport {
    return {
        ...format,
        supported: false,
        hardwareAccelerated: false,
        sustainedRate: false,
        error: { kind: 'platform-error', message },
    };
}

export async function queryVideoFormatSupport(
    formats: readonly VideoFormatQuery[],
): Promise<VideoFormatSupport[]> {
    const results: Array<VideoFormatSupport | undefined> = Array.from({
        length: formats.length,
    });
    const validFormats: VideoFormatQuery[] = [];
    const validIndexes: number[] = [];

    formats.forEach((format, index) => {
        const error = validate(format);
        if (error) {
            results[index] = invalidCandidate(format, error);
        } else {
            validFormats.push({ ...format });
            validIndexes.push(index);
        }
    });

    if (validFormats.length > 0) {
        try {
            const nativeResults = await queryNativeVideoFormatSupport(validFormats);
            if (nativeResults.length !== validFormats.length) {
                throw new Error(
                    `native module returned ${nativeResults.length} results for ${validFormats.length} formats`,
                );
            }
            validIndexes.forEach((resultIndex, nativeIndex) => {
                results[resultIndex] = combine(
                    validFormats[nativeIndex],
                    nativeResults[nativeIndex],
                );
            });
        } catch (caught: unknown) {
            const message = caught instanceof Error ? caught.message : String(caught);
            validIndexes.forEach((resultIndex, nativeIndex) => {
                results[resultIndex] = platformFailure(validFormats[nativeIndex], message);
            });
        }
    }

    return results as VideoFormatSupport[];
}
