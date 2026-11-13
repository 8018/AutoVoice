# Open ASR replacement comparison

User approved the preceding comparison and implementation approach (做吧). Replace the expired paid offline command dependency only after measured acceptance. Preserve wake/cloud and existing uncommitted echo work.

Compare Chinese streaming Zipformer against SenseVoice Small via the existing sherpa-onnx Android runtime. Vosk is a lightweight fallback; Whisper/FunASR/WeNet are deferred because this first comparison targets Android short commands and reuses the installed runtime. This selection is a test hypothesis, not a quality claim.

Deliver a repeatable versioned, checksum-pinned public synthetic audio benchmark and an Android instrumentation runner, with both decoders, isolated from business execution. Record per-case transcript, exact normalized correctness, CER, model load/decode time, real-time factor, and resource measurements with honest scopes. Streaming feed is simulated file chunks, not live microphone end-to-end latency. Missing audio/models/results, duplicate cases, corrupt assets and native failures must fail, never skip to green.

Include navigation ordinals, cancel, paging, destination names, full sentences, silence/noise and synthetic overlapping playback. Synthetic overlap measures ASR robustness only; acoustic AEC and human double-talk remain a separate mandatory unpassed acceptance gate. Public fixtures only, no private recordings, no map/car actions. Keep model artifacts out of git; store manifests/provenance and reproducible scripts. No production model switch based solely on synthetic speech.

The existing full dev text/route/voice/statistics gate remains required; this adds an ASR channel, not a replacement. Device access or native/model incompatibility must be recorded as blocked. No paid entitlement purchase. Each downloaded model has separate source revision, license and hashes. A runtime license does not license model weights.
