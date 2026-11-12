#include "frontend_session.h"
#include "api/audio/builtin_audio_processing_builder.h"
#include "api/environment/environment_factory.h"

namespace autovoice {
FrontendSession::~FrontendSession() {
  for (auto* vad : vad_) if (vad) WebRtcVad_Free(vad);
}
bool FrontendSession::Initialize(int aggressiveness) {
  if (aggressiveness < 0 || aggressiveness > 3) return false;
  aggressiveness_ = aggressiveness;
  webrtc::AudioProcessing::Config config;
  config.echo_canceller.enabled = true;
  config.high_pass_filter.enabled = true;
  config.noise_suppression.enabled = true;
  config.noise_suppression.level = webrtc::AudioProcessing::Config::NoiseSuppression::kModerate;
  config.gain_controller1.enabled = false;
  config.gain_controller2.enabled = false;
  apm_ = webrtc::BuiltinAudioProcessingBuilder(config).Build(webrtc::CreateEnvironment());
  if (!apm_) return false;
  for (auto& vad : vad_) {
    vad = WebRtcVad_Create();
    if (!vad || WebRtcVad_Init(vad) != 0 || WebRtcVad_set_mode(vad, aggressiveness) != 0) return false;
  }
  return true;
}
int FrontendSession::Capture(const int16_t* input, int16_t* output, std::array<int, kPurposes>& decisions, int delay_ms, int valid_samples) {
  std::lock_guard<std::mutex> guard(lock_);
  const webrtc::StreamConfig stream(16000, 1);
  int status = apm_->set_stream_delay_ms(delay_ms);
  if (status != 0) return status;
  status = apm_->ProcessStream(input, stream, stream, output);
  if (status != 0) return status;
  for (int i = 0; i < kPurposes; ++i) {
    // Padding is DSP-only. It must not advance any detector's silence state.
    decisions[i] = valid_samples == kSamples ? WebRtcVad_Process(vad_[i], 16000, output, kSamples) : -1;
    if (valid_samples == kSamples && decisions[i] < 0) return -1;
  }
  return 0;
}
int FrontendSession::Render(const int16_t* input) {
  std::lock_guard<std::mutex> guard(lock_);
  std::array<int16_t, kSamples> output{};
  const webrtc::StreamConfig stream(16000, 1);
  return apm_->ProcessReverseStream(input, stream, stream, output.data());
}
int FrontendSession::ResetVad(int purpose) {
  std::lock_guard<std::mutex> guard(lock_);
  if (purpose < 0 || purpose >= kPurposes) return -1;
  int status = WebRtcVad_Init(vad_[purpose]);
  return status == 0 ? WebRtcVad_set_mode(vad_[purpose], aggressiveness_) : status;
}
}  // namespace autovoice
