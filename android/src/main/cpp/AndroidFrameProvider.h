#pragma once

#include <android/hardware_buffer.h>
#include <jni.h>

#include <mutex>
#include <string>

#include "FrameSourceHostObject.h"

namespace videotexture {

/// Pure-C++ frame latch: Kotlin pushes decoded AHardwareBuffers in (JNI), the
/// JSI host object pulls +1-retained handles out (worklet thread, no JNI).
class AndroidFrameProvider : public FrameProvider {
 public:
  explicit AndroidFrameProvider(std::string pixelFormat);
  ~AndroidFrameProvider() override;

  // Producer side (JNI, decoder thread). `ptsSec` is forward media time for this frame and
  // travels with it so the consumer cannot simulate against a different frame than it draws.
  void pushFrame(AHardwareBuffer *buffer, double ptsSec, int64_t generation);
  void clearLatest();
  void updateTransport(std::string uri, int status, int64_t statusSeq, int64_t errorSeq,
                       std::string errorMessage, double durationSec, double actualRate,
                       int64_t generation);
  void attachCommandTarget(JNIEnv *env, jobject target);
  void detachCommandTarget(JNIEnv *env);

  // FrameProvider (worklet thread).
  AcquiredFrame copyNewFrame() override;
  TransportSnapshot transportSnapshot() override;
  void loadClip(const std::string &uri, double startSec, int64_t generation,
                const std::string &loopMode, bool autoPlay) override;
  void setPaused(bool paused) override;
  void setRate(double rate) override;
  void rampRate(double rate, double durationMs) override;
  void setVolume(double volume) override;
  void releaseHandle(void *handle) override;
  std::string pixelFormat() override;

 private:
  std::mutex mutex_;
  AHardwareBuffer *latest_ = nullptr;
  bool latestIsNew_ = false;
  double latestPtsSec_ = -1.0;
  int64_t latestGeneration_ = 0;
  TransportSnapshot transport_;
  std::mutex commandMutex_;
  JavaVM *jvm_ = nullptr;
  jobject commandTarget_ = nullptr;
  jmethodID loadClipMethod_ = nullptr;
  jmethodID setPausedMethod_ = nullptr;
  jmethodID setRateMethod_ = nullptr;
  jmethodID rampRateMethod_ = nullptr;
  jmethodID setVolumeMethod_ = nullptr;
  const std::string pixelFormat_;
};

}  // namespace videotexture
