#pragma once

#include <jsi/jsi.h>

#include <memory>
#include <string>

namespace videotexture {

/// A decoded frame plus the metadata the render loop needs to simulate against it.
///
/// The timestamp travels WITH the pixels deliberately. Previously the render loop got its
/// pixels from this provider and its media time from a separate native->JS->worklet hop, so
/// the two raced: world state was sampled for one moment while a different moment's image
/// was on screen, and which one depended on thread scheduling.
struct AcquiredFrame {
  /// +1-retained platform handle, or nullptr when nothing new was decoded.
  void *handle = nullptr;
  /// Media time of THIS frame, in seconds — the same timeline `currentTimeSec` reports.
  /// Negative when unknown.
  double ptsSec = -1.0;
  /// Clip generation this frame belongs to; bumped on every loadClip. Lets the consumer
  /// distinguish a seek from a clip swap without guessing from a time discontinuity.
  int64_t generation = 0;
};

struct TransportSnapshot {
  std::string uri;
  int status = 0;
  int64_t statusSeq = 0;
  int64_t errorSeq = 0;
  std::string errorMessage;
  double durationSec = 0.0;
  double actualRate = 0.0;
  int64_t generation = 0;
};

/// Provider of +1-retained opaque frame handles. Platforms bind retain/release:
/// CFRetain/CFRelease (CVPixelBufferRef) on iOS, AHardwareBuffer_acquire/_release
/// on Android.
class FrameProvider {
 public:
  virtual ~FrameProvider() = default;
  virtual AcquiredFrame copyNewFrame() = 0;  // +1, or handle == nullptr when nothing new
  virtual TransportSnapshot transportSnapshot() = 0;
  /// endSec bounds the playable region in ABSOLUTE file seconds (<= 0 = no bound):
  /// 'off' reports ended there; 'loop' loops [startSec, endSec].
  virtual void loadClip(const std::string &uri, double startSec, double endSec,
                        int64_t generation, const std::string &loopMode, bool autoPlay) = 0;
  virtual void setPaused(bool paused) = 0;
  virtual void setRate(double rate) = 0;
  virtual void rampRate(double rate, double durationMs) = 0;
  virtual void setVolume(double volume) = 0;
  virtual void releaseHandle(void *handle) = 0;
  virtual std::string pixelFormat() = 0;  // "bgra8" | "nv12"
};

/// Installs a jsi::HostObject for the provider under
/// `globalThis.__videoTextureFrameSources[key]`: poll()
/// ({handle, ptsSec, generation} or null), releaseFrame(handle), releaseAll(), pixelFormat.
/// Worklet runtimes reach it by closure capture (react-native-worklets shares
/// the HostObject shared_ptr).
void installFrameSource(facebook::jsi::Runtime &rt,
                        std::shared_ptr<FrameProvider> provider,
                        const std::string &key);

}  // namespace videotexture
