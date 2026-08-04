#import "FrameSourceJSI.h"

#include <jsi/jsi.h>

#include <memory>
#include <string>

#include "FrameSourceHostObject.h"

namespace {

/// Binds the ObjC frame provider to the shared host object; releaseHandle is
/// CFRelease (handles are +1-retained CVPixelBufferRefs).
class ObjCFrameProvider : public videotexture::FrameProvider {
public:
  explicit ObjCFrameProvider(id<VideoTextureFrameProviding> provider) : provider_(provider) {}

  videotexture::AcquiredFrame copyNewFrame() override {
    videotexture::AcquiredFrame frame;
    frame.handle = (void *)[provider_ copyNewFrame];
    if (frame.handle) {
      // Only meaningful for a frame that was actually handed over — see the protocol doc.
      frame.ptsSec = [provider_ lastFramePtsSec];
      frame.generation = [provider_ lastFrameGeneration];
    }
    return frame;
  }
  videotexture::TransportSnapshot transportSnapshot() override {
    VideoTextureTransportSnapshot *value = [provider_ transportSnapshot];
    videotexture::TransportSnapshot snapshot;
    if (value.uri) snapshot.uri = [value.uri UTF8String];
    snapshot.status = value.status;
    snapshot.statusSeq = value.statusSeq;
    snapshot.errorSeq = value.errorSeq;
    if (value.errorMessage) snapshot.errorMessage = [value.errorMessage UTF8String];
    snapshot.durationSec = value.durationSec;
    snapshot.actualRate = value.actualRate;
    snapshot.generation = value.generation;
    return snapshot;
  }
  void loadClip(const std::string &uri, double startSec, int64_t generation,
                const std::string &loopMode, bool autoPlay) override {
    [provider_ loadClipWithUri:[NSString stringWithUTF8String:uri.c_str()]
                      startSec:startSec
                    generation:generation
                      loopMode:[NSString stringWithUTF8String:loopMode.c_str()]
                      autoPlay:autoPlay];
  }
  void setPaused(bool paused) override { [provider_ setPausedFromRuntime:paused]; }
  void setRate(double rate) override { [provider_ setRateFromRuntime:rate]; }
  void rampRate(double rate, double durationMs) override {
    [provider_ rampRateFromRuntime:rate durationMs:durationMs];
  }
  void setVolume(double volume) override { [provider_ setVolumeFromRuntime:volume]; }
  void releaseHandle(void *handle) override { CFRelease(handle); }
  std::string pixelFormat() override { return [[provider_ pixelFormat] UTF8String]; }

private:
  id<VideoTextureFrameProviding> provider_;
};

} // namespace

@implementation VideoTextureTransportSnapshot

- (instancetype)initWithUri:(NSString *)uri
                      status:(int)status
                   statusSeq:(int64_t)statusSeq
                    errorSeq:(int64_t)errorSeq
                errorMessage:(NSString *)errorMessage
                 durationSec:(double)durationSec
                  actualRate:(double)actualRate
                  generation:(int64_t)generation {
  self = [super init];
  if (self) {
    _uri = [uri copy];
    _status = status;
    _statusSeq = statusSeq;
    _errorSeq = errorSeq;
    _errorMessage = [errorMessage copy];
    _durationSec = durationSec;
    _actualRate = actualRate;
    _generation = generation;
  }
  return self;
}

@end

@implementation VideoTextureFrameSourceInstaller

+ (void)installWithRuntimePointer:(void *)runtimePointer
                         provider:(id<VideoTextureFrameProviding>)provider
                              key:(NSString *)key {
  auto *runtime = reinterpret_cast<facebook::jsi::Runtime *>(runtimePointer);
  videotexture::installFrameSource(*runtime, std::make_shared<ObjCFrameProvider>(provider),
                                   [key UTF8String]);
}

@end
