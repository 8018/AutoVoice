#include <jni.h>
#include <array>
#include <atomic>
#include <memory>
#include <mutex>
#include <unordered_map>
#include "frontend_session.h"

namespace {
using Session = autovoice::FrontendSession;
std::mutex handles_lock;
std::unordered_map<jlong, std::shared_ptr<Session>> handles;
std::atomic<jlong> next_handle{1};
void Error(JNIEnv* env, const char* type, const char* message) {
  if (env->ExceptionCheck()) return;
  jclass cls = env->FindClass(type);
  if (cls) env->ThrowNew(cls, message);
}
std::shared_ptr<Session> Get(JNIEnv* env, jlong handle) {
  std::lock_guard<std::mutex> guard(handles_lock);
  auto it = handles.find(handle);
  if (it == handles.end()) { Error(env, "java/lang/IllegalStateException", "WebRTC session released");return nullptr; }
  return it->second;
}
bool Samples(JNIEnv* env, jshortArray input) {
  if (input && env->GetArrayLength(input) == Session::kSamples) return true;
  Error(env, "java/lang/IllegalArgumentException", "WebRTC requires 160 mono PCM16 samples at 16kHz");return false;
}
}
extern "C" JNIEXPORT jlong JNICALL Java_com_autovoice_adapterwebrtc_WebRtcNative_create(JNIEnv* env, jclass, jint mode) {
  if (mode < 0 || mode > 3) { Error(env,"java/lang/IllegalArgumentException","VAD aggressiveness must be 0..3");return 0; }
  auto session = std::make_shared<Session>();
  if (!session->Initialize(mode)) { Error(env,"java/lang/IllegalStateException","WebRTC APM/VAD creation failed");return 0; }
  jlong handle = next_handle.fetch_add(1);
  std::lock_guard<std::mutex> guard(handles_lock);handles.emplace(handle,std::move(session));return handle;
}
extern "C" JNIEXPORT jint JNICALL Java_com_autovoice_adapterwebrtc_WebRtcNative_processCapture(
    JNIEnv* env,jclass,jlong handle,jshortArray input,jshortArray output,jintArray vad,jint delay,jint valid) {
  if (!Samples(env,input) || !Samples(env,output)) return -1;
  if (!vad || env->GetArrayLength(vad) != 3 || delay < 0 || delay > 500 || valid <= 0 || valid > 160) {
    Error(env,"java/lang/IllegalArgumentException","Invalid WebRTC capture metadata");return -1;
  }
  auto session = Get(env,handle);if (!session) return -1;
  std::array<int16_t,160> source{},processed{};std::array<int,3> decisions{};
  env->GetShortArrayRegion(input,0,160,source.data());if (env->ExceptionCheck()) return -1;
  int status = session->Capture(source.data(),processed.data(),decisions,delay,valid);
  if (status != 0) { Error(env,"java/lang/IllegalStateException","WebRTC capture processing failed");return status; }
  env->SetShortArrayRegion(output,0,160,processed.data());env->SetIntArrayRegion(vad,0,3,decisions.data());return 0;
}
extern "C" JNIEXPORT jint JNICALL Java_com_autovoice_adapterwebrtc_WebRtcNative_processRender(JNIEnv* env,jclass,jlong handle,jshortArray input) {
  if (!Samples(env,input)) return -1;
  auto session = Get(env,handle);if (!session) return -1;
  std::array<int16_t,160> source{};env->GetShortArrayRegion(input,0,160,source.data());if (env->ExceptionCheck()) return -1;
  int status = session->Render(source.data());
  if (status != 0) Error(env,"java/lang/IllegalStateException","WebRTC render processing failed");return status;
}
extern "C" JNIEXPORT jint JNICALL Java_com_autovoice_adapterwebrtc_WebRtcNative_resetVad(JNIEnv* env,jclass,jlong handle,jint purpose) {
  auto session = Get(env,handle);if (!session) return -1;
  int status = session->ResetVad(purpose);
  if (status != 0) Error(env,"java/lang/IllegalArgumentException","Invalid WebRTC VAD purpose");return status;
}
extern "C" JNIEXPORT void JNICALL Java_com_autovoice_adapterwebrtc_WebRtcNative_release(JNIEnv*,jclass,jlong handle) {
  // In-flight calls hold a shared lease; removing the handle never frees their session early.
  std::lock_guard<std::mutex> guard(handles_lock);handles.erase(handle);
}
