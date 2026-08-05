// JNI surface for the Android frame source. Kotlin owns provider lifetime via
// nativeCreate/nativeDestroy; the HostObject copies the shared_ptr so straggling
// worklet calls stay safe after destroy.
#include <android/hardware_buffer_jni.h>
#include <jni.h>
#include <jsi/jsi.h>

#include <memory>
#include <string>

#include "AndroidFrameProvider.h"
#include "FrameSourceHostObject.h"

using videotexture::AndroidFrameProvider;

namespace {

using ProviderHolder = std::shared_ptr<AndroidFrameProvider>;

ProviderHolder &holder(jlong ptr) {
  return *reinterpret_cast<ProviderHolder *>(ptr);
}

std::string toStdString(JNIEnv *env, jstring str) {
  const char *chars = env->GetStringUTFChars(str, nullptr);
  std::string result(chars);
  env->ReleaseStringUTFChars(str, chars);
  return result;
}

}  // namespace

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM *, void *) {
  return JNI_VERSION_1_6;
}

JNIEXPORT jlong JNICALL Java_expo_modules_videotexture_FrameSourceNative_nativeCreate(
    JNIEnv *env, jclass, jstring pixelFormat) {
  auto *ptr = new ProviderHolder(std::make_shared<AndroidFrameProvider>(toStdString(env, pixelFormat)));
  return reinterpret_cast<jlong>(ptr);
}

JNIEXPORT void JNICALL Java_expo_modules_videotexture_FrameSourceNative_nativeDestroy(
    JNIEnv *, jclass, jlong providerPtr) {
  delete reinterpret_cast<ProviderHolder *>(providerPtr);
}

JNIEXPORT void JNICALL Java_expo_modules_videotexture_FrameSourceNative_nativeInstall(
    JNIEnv *env, jclass, jlong runtimePtr, jlong providerPtr, jstring key) {
  auto &rt = *reinterpret_cast<facebook::jsi::Runtime *>(runtimePtr);
  videotexture::installFrameSource(rt, holder(providerPtr), toStdString(env, key));
}

JNIEXPORT void JNICALL Java_expo_modules_videotexture_FrameSourceNative_nativeAttachCommandTarget(
    JNIEnv *env, jclass, jlong providerPtr, jobject target) {
  holder(providerPtr)->attachCommandTarget(env, target);
}

JNIEXPORT void JNICALL Java_expo_modules_videotexture_FrameSourceNative_nativeDetachCommandTarget(
    JNIEnv *env, jclass, jlong providerPtr) {
  holder(providerPtr)->detachCommandTarget(env);
}

JNIEXPORT jlong JNICALL Java_expo_modules_videotexture_FrameSourceNative_nativePushFrame(
    JNIEnv *env, jclass, jlong providerPtr, jobject hardwareBuffer, jdouble ptsSec,
    jlong generation) {
  AHardwareBuffer *buffer = AHardwareBuffer_fromHardwareBuffer(env, hardwareBuffer);
  return static_cast<jlong>(holder(providerPtr)->pushFrame(buffer, ptsSec, generation));
}

JNIEXPORT void JNICALL Java_expo_modules_videotexture_FrameSourceNative_nativeClearLatest(
    JNIEnv *, jclass, jlong providerPtr) {
  holder(providerPtr)->clearLatest();
}

JNIEXPORT void JNICALL Java_expo_modules_videotexture_FrameSourceNative_nativeUpdateTransport(
    JNIEnv *env, jclass, jlong providerPtr, jstring uri, jint status, jlong statusSeq,
    jlong errorSeq, jstring errorMessage, jdouble durationSec, jdouble actualRate,
    jlong generation) {
  std::string uriValue = uri ? toStdString(env, uri) : std::string();
  std::string message = errorMessage ? toStdString(env, errorMessage) : std::string();
  holder(providerPtr)->updateTransport(std::move(uriValue), status, statusSeq, errorSeq,
                                       std::move(message), durationSec, actualRate, generation);
}

}  // extern "C"
