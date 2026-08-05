#include "AndroidFrameProvider.h"

#include <android/log.h>

namespace videotexture {

AndroidFrameProvider::AndroidFrameProvider(std::string pixelFormat)
    : pixelFormat_(std::move(pixelFormat)) {}

AndroidFrameProvider::~AndroidFrameProvider() {
  clearLatest();
  if (jvm_) {
    JNIEnv *env = nullptr;
    bool attached = jvm_->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK;
    if (attached) jvm_->AttachCurrentThread(&env, nullptr);
    if (env) detachCommandTarget(env);
    if (attached) jvm_->DetachCurrentThread();
  }
}

void AndroidFrameProvider::attachCommandTarget(JNIEnv *env, jobject target) {
  std::lock_guard<std::mutex> lock(commandMutex_);
  env->GetJavaVM(&jvm_);
  if (commandTarget_) {
    env->DeleteGlobalRef(commandTarget_);
  }
  commandTarget_ = env->NewGlobalRef(target);
  jclass cls = env->GetObjectClass(target);
  loadClipMethod_ = env->GetMethodID(cls, "dispatchLoadClipFromNative",
                                     "(Ljava/lang/String;DDILjava/lang/String;Z)V");
  setPausedMethod_ = env->GetMethodID(cls, "dispatchSetPausedFromNative", "(Z)V");
  setRateMethod_ = env->GetMethodID(cls, "dispatchSetRateFromNative", "(D)V");
  rampRateMethod_ = env->GetMethodID(cls, "dispatchRampRateFromNative", "(DD)V");
  setVolumeMethod_ = env->GetMethodID(cls, "dispatchSetVolumeFromNative", "(D)V");
  releaseFrameMethod_ = env->GetMethodID(cls, "dispatchReleaseFrameFromNative", "(J)V");
  env->DeleteLocalRef(cls);
}

void AndroidFrameProvider::detachCommandTarget(JNIEnv *env) {
  std::lock_guard<std::mutex> lock(commandMutex_);
  if (commandTarget_) {
    env->DeleteGlobalRef(commandTarget_);
    commandTarget_ = nullptr;
  }
  loadClipMethod_ = nullptr;
  setPausedMethod_ = nullptr;
  setRateMethod_ = nullptr;
  rampRateMethod_ = nullptr;
  setVolumeMethod_ = nullptr;
  releaseFrameMethod_ = nullptr;
}

namespace {
template <typename Call>
void withEnv(JavaVM *jvm, Call call) {
  if (!jvm) return;
  JNIEnv *env = nullptr;
  bool attached = jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK;
  if (attached && jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
  call(env);
  if (env->ExceptionCheck()) env->ExceptionClear();
  if (attached) jvm->DetachCurrentThread();
}
}  // namespace

uint64_t AndroidFrameProvider::pushFrame(AHardwareBuffer *buffer, double ptsSec,
                                         int64_t generation) {
  if (!buffer) {
    return 0;
  }
  AHardwareBuffer_acquire(buffer);

  AHardwareBuffer *unconsumed = nullptr;
  {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!loggedBufferDescription_) {
      AHardwareBuffer_Desc desc{};
      AHardwareBuffer_describe(buffer, &desc);
      __android_log_print(
          ANDROID_LOG_WARN, "VideoTextureColor",
          "AHardwareBuffer width=%u height=%u layers=%u format=0x%x usage=0x%llx stride=%u",
          desc.width, desc.height, desc.layers, desc.format,
          static_cast<unsigned long long>(desc.usage), desc.stride);
      loggedBufferDescription_ = true;
    }
    if (latest_) {
      if (latestIsNew_) unconsumed = latest_;
      AHardwareBuffer_release(latest_);
    }
    latest_ = buffer;
    latestIsNew_ = true;
    latestPtsSec_ = ptsSec;
    latestGeneration_ = generation;
  }
  if (unconsumed) releaseImage(unconsumed);
  return reinterpret_cast<uint64_t>(buffer);
}

void AndroidFrameProvider::clearLatest() {
  AHardwareBuffer *unconsumed = nullptr;
  {
    std::lock_guard<std::mutex> lock(mutex_);
    if (latest_) {
      if (latestIsNew_) unconsumed = latest_;
      AHardwareBuffer_release(latest_);
      latest_ = nullptr;
    }
    latestIsNew_ = false;
    latestPtsSec_ = -1.0;
  }
  if (unconsumed) releaseImage(unconsumed);
}

void AndroidFrameProvider::updateTransport(std::string uri, int status, int64_t statusSeq,
                                           int64_t errorSeq, std::string errorMessage,
                                           double durationSec, double actualRate,
                                           int64_t generation) {
  std::lock_guard<std::mutex> lock(mutex_);
  transport_.uri = std::move(uri);
  transport_.status = status;
  transport_.statusSeq = statusSeq;
  transport_.errorSeq = errorSeq;
  transport_.errorMessage = std::move(errorMessage);
  transport_.durationSec = durationSec;
  transport_.actualRate = actualRate;
  transport_.generation = generation;
}

AcquiredFrame AndroidFrameProvider::copyNewFrame() {
  std::lock_guard<std::mutex> lock(mutex_);
  if (!latest_ || !latestIsNew_) {
    return {};
  }
  latestIsNew_ = false;
  AHardwareBuffer_acquire(latest_);
  return AcquiredFrame{latest_, latestPtsSec_, latestGeneration_};
}

TransportSnapshot AndroidFrameProvider::transportSnapshot() {
  std::lock_guard<std::mutex> lock(mutex_);
  return transport_;
}

void AndroidFrameProvider::loadClip(const std::string &uri, double startSec, double endSec,
                                    int64_t generation, const std::string &loopMode,
                                    bool autoPlay) {
  withEnv(jvm_, [&](JNIEnv *env) {
    std::lock_guard<std::mutex> lock(commandMutex_);
    if (!commandTarget_ || !loadClipMethod_) return;
    jstring jUri = env->NewStringUTF(uri.c_str());
    jstring jLoopMode = env->NewStringUTF(loopMode.c_str());
    env->CallVoidMethod(commandTarget_, loadClipMethod_, jUri, startSec, endSec,
                        static_cast<jint>(generation), jLoopMode, static_cast<jboolean>(autoPlay));
    env->DeleteLocalRef(jUri);
    env->DeleteLocalRef(jLoopMode);
  });
}

void AndroidFrameProvider::setPaused(bool paused) {
  withEnv(jvm_, [&](JNIEnv *env) {
    std::lock_guard<std::mutex> lock(commandMutex_);
    if (commandTarget_ && setPausedMethod_)
      env->CallVoidMethod(commandTarget_, setPausedMethod_, static_cast<jboolean>(paused));
  });
}

void AndroidFrameProvider::setRate(double rate) {
  withEnv(jvm_, [&](JNIEnv *env) {
    std::lock_guard<std::mutex> lock(commandMutex_);
    if (commandTarget_ && setRateMethod_) env->CallVoidMethod(commandTarget_, setRateMethod_, rate);
  });
}

void AndroidFrameProvider::rampRate(double rate, double durationMs) {
  withEnv(jvm_, [&](JNIEnv *env) {
    std::lock_guard<std::mutex> lock(commandMutex_);
    if (commandTarget_ && rampRateMethod_)
      env->CallVoidMethod(commandTarget_, rampRateMethod_, rate, durationMs);
  });
}

void AndroidFrameProvider::setVolume(double volume) {
  withEnv(jvm_, [&](JNIEnv *env) {
    std::lock_guard<std::mutex> lock(commandMutex_);
    if (commandTarget_ && setVolumeMethod_)
      env->CallVoidMethod(commandTarget_, setVolumeMethod_, volume);
  });
}

void AndroidFrameProvider::releaseHandle(void *handle) {
  releaseImage(static_cast<AHardwareBuffer *>(handle));
  AHardwareBuffer_release(static_cast<AHardwareBuffer *>(handle));
}

void AndroidFrameProvider::releaseImage(AHardwareBuffer *buffer) {
  withEnv(jvm_, [&](JNIEnv *env) {
    jobject target = nullptr;
    jmethodID method = nullptr;
    {
      std::lock_guard<std::mutex> lock(commandMutex_);
      if (commandTarget_ && releaseFrameMethod_) {
        target = env->NewGlobalRef(commandTarget_);
        method = releaseFrameMethod_;
      }
    }
    if (target) {
      env->CallVoidMethod(target, method,
                          static_cast<jlong>(reinterpret_cast<uint64_t>(buffer)));
      env->DeleteGlobalRef(target);
    }
  });
}

std::string AndroidFrameProvider::pixelFormat() {
  return pixelFormat_;
}

}  // namespace videotexture
