#ifndef AUTOVOICE_FRONTEND_SESSION_H_
#define AUTOVOICE_FRONTEND_SESSION_H_
#include <array>
#include <cstdint>
#include <mutex>
#include "api/audio/audio_processing.h"
#include "api/scoped_refptr.h"
#include "common_audio/vad/include/webrtc_vad.h"

namespace autovoice {
class FrontendSession {
 public:
  static constexpr int kSamples = 160;
  static constexpr int kPurposes = 3;
  ~FrontendSession();
  bool Initialize(int aggressiveness);
  int Capture(const int16_t* input, int16_t* output, std::array<int, kPurposes>& decisions, int delay_ms, int valid_samples);
  int Render(const int16_t* input);
  int ResetVad(int purpose);
 private:
  std::mutex lock_;
  webrtc::scoped_refptr<webrtc::AudioProcessing> apm_;
  std::array<VadInst*, kPurposes> vad_{};
  int aggressiveness_ = 0;
};
}  // namespace autovoice
#endif
