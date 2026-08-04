#import <CoreVideo/CoreVideo.h>
#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

@interface VideoTextureTransportSnapshot : NSObject
@property(nonatomic, copy, nullable, readonly) NSString *uri;
@property(nonatomic, readonly) int status;
@property(nonatomic, readonly) int64_t statusSeq;
@property(nonatomic, readonly) int64_t errorSeq;
@property(nonatomic, copy, nullable, readonly) NSString *errorMessage;
@property(nonatomic, readonly) double durationSec;
@property(nonatomic, readonly) double actualRate;
@property(nonatomic, readonly) int64_t generation;
- (instancetype)initWithUri:(nullable NSString *)uri
                      status:(int)status
                   statusSeq:(int64_t)statusSeq
                    errorSeq:(int64_t)errorSeq
                errorMessage:(nullable NSString *)errorMessage
                 durationSec:(double)durationSec
                  actualRate:(double)actualRate
                  generation:(int64_t)generation;
@end

/// Thread-safe frame provider implemented on the Swift side (VideoTexturePlayer).
/// copyNewFrame returns a +1-retained CVPixelBuffer, or NULL when nothing new has
/// been decoded since the last call.
///
/// `lastFramePtsSec` / `lastFrameGeneration` describe the frame the most recent
/// copyNewFrame returned, and are only valid immediately after a non-NULL result. They are
/// separate methods rather than out-params because an ObjC protocol cannot vend the Swift
/// struct these come from; the caller reads all three in sequence on one thread.
@protocol VideoTextureFrameProviding <NSObject>
- (nullable CVPixelBufferRef)copyNewFrame CF_RETURNS_RETAINED;
/// Media time of that frame in seconds. Negative if unknown.
- (double)lastFramePtsSec;
/// Clip generation that frame belongs to.
- (int64_t)lastFrameGeneration;
- (VideoTextureTransportSnapshot *)transportSnapshot;
- (void)loadClipWithUri:(NSString *)uri
               startSec:(double)startSec
             generation:(int64_t)generation
               loopMode:(NSString *)loopMode
               autoPlay:(BOOL)autoPlay;
- (void)setPausedFromRuntime:(BOOL)paused;
- (void)setRateFromRuntime:(double)rate;
- (void)rampRateFromRuntime:(double)rate durationMs:(double)durationMs;
- (void)setVolumeFromRuntime:(double)volume;
- (NSString *)pixelFormat;
@end

/// Installs a jsi::HostObject for the provider under
/// `globalThis.__videoTextureFrameSources[key]`. The HostObject exposes
/// poll() (transport snapshot plus an optional frame),
/// releaseFrame(handle), releaseAll(), and pixelFormat.
/// Worklet runtimes reach it by closure capture (react-native-worklets shares
/// the HostObject shared_ptr across runtimes).
/// `runtimePointer` is a `facebook::jsi::Runtime *`, obtained from
/// `JavaScriptRuntime.withUnsafePointee`. Kept as void* so this header needs neither Expo nor
/// JSI types — Swift silently drops methods whose parameter types it cannot resolve.
@interface VideoTextureFrameSourceInstaller : NSObject
+ (void)installWithRuntimePointer:(void *)runtimePointer
                         provider:(id<VideoTextureFrameProviding>)provider
                              key:(NSString *)key
    NS_SWIFT_NAME(install(runtimePointer:provider:key:));
@end

NS_ASSUME_NONNULL_END
