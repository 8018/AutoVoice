# Open ASR Comparison Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Compare two open offline Chinese ASR candidates on Android using an expandable reproducible benchmark before replacing the main recognizer.
**Architecture:** Test-only Android recognizers consume fixed model manifests and PCM fixtures under app external files. Host tools prepare public fixtures and strictly score complete per-model runs. Production wake/cloud and previous echo changes are preserved.
**Tech Stack:** Kotlin Android instrumentation, existing sherpa-onnx JNI, Node test runner, Python asset preparation where needed.
**Spec:** docs/superpowers/specs/2026-11-14-open-asr-comparison-design.md

## Global Constraints
- No private speech, paid license workarounds or map/car execution.
- Keep wake/cloud and prior changes intact; no automatic production switch before acoustic acceptance.
- Version suites, preserve old cases, pin asset hashes and model provenance; fail closed on missing results/resources.
- Separate synthetic decode timing from real microphone interruption timing.
- Run AGENTS.md full dev gates; never claim unrun hardware acceptance passed.

## Review Focus
- Partial/corrupt/missing model or audio must fail before native loading.
- Silent/noisy fixtures must not be counted as successful navigation commands.
- Missing/duplicate results must not improve aggregate success.
- Streaming tail, sample rate and PCM conversion must preserve full input.
- Synthetic overlap must never be labeled acoustic echo cancellation acceptance.

### Task 1: Android comparison harness
- [x] Add test-only instrumented runner under app/src/androidTestOpen using OnlineRecognizer Zipformer and OfflineRecognizer SenseVoice.
- [x] Read model manifest/fixture suite at fixed app external path; validate SHA-256 and sizes; validate PCM16 mono 16k WAV.
- [x] Measure load and decode separately, identify runtime/device, process both models sequentially, save JSON report even on errors; close streams/recognizers.
- [x] Compile Android test APK, review and fix harness before device execution.

### Task 2: Host suite and repeatable assets/scoring
- [x] Create versioned public navigation ASR cases, fixture generation via existing dev TTS plus silence/noise/overlap derivatives.
- [x] Add strict scoring tests first: normalization, CER, missing/duplicate cases, timing and hash metadata, no false green.
- [x] Implement scorer and preparation runner with documented immutable manifests.
- [x] Download official models with provenance, checksums and license artifacts; run same fixtures on both.

### Task 3: Verification and review
- [x] Run Node and appropriate Android tests, full dev navigation gates.
- [x] Connect existing phone, install only needed test build and run comparison; request new port only if connection unavailable.
- [x] Review diff, fix defects, report model results and unresolved acoustic gate; do not replace production if not accepted.

## Final result

Comparison harness and fixed public fixtures delivered. Two raw runs: Zipformer 25/29, SenseVoice 27/29. Three completed Silero-admitted runs: SenseVoice 29/29; Zipformer 25/29 at 500 ms tail, 27/29 at 1000 ms. Third gated attempt lost connection during pull; its complete device checkpoint was later recovered and its APK/input identities verified before scoring. Selected next integration candidate: SenseVoice plus Silero; production switch remains gated by live microphone/acoustic acceptance and application lifecycle integration. Final Node 38, device input 11, app Open 261 and Iflytek 255 tests passed; all four verified dev gates passed. Independent review findings on coverage, APK identity, failed-attempt retention and raw/gated metadata separation addressed. See docs/navigation-open-asr-comparison.md.
