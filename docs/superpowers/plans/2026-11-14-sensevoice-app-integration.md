# SenseVoice App Integration Plan

> **For agentic workers:** Use subagent-driven-development with independent review. User authorized open-source replacement and supplied device port to continue.

**Goal:** Make the winning SenseVoice + Silero candidate usable in the Open Android test app while keeping Xfyun wake and cloud recognition.
**Architecture:** Reuse the existing serialized offline-ASR owner/admission/lifecycle and JNI runtime. Add a pinned SenseVoice catalog/loader/decoder with Silero admission, wire Open assets and reporting to the actual provider. Preserve original Xfyun app and existing uncommitted echo changes. No claim of acoustic acceptance from file tests.
**Tech Stack:** Kotlin Android, sherpa-onnx 2.0.1 / ORT 1.28.2, existing Xfyun wake, WebRTC frontend, Node/Python QA.
**Spec:** docs/navigation-open-asr-comparison.md (measured candidate and outstanding application integration gates).

## Constraints
- No paid entitlement workarounds. Preserve wake/cloud and cancellation/admission ownership.
- Model catalog and licenses pinned; no silent fallback to FireRed or fake recognition.
- No changes to current Xfyun installed app/data. Open candidate installation is reversible.
- Native model construction/decode/release on serialized owner, bounded work/deadlines and stale callback suppression.
- Silero admission uses 512 new samples + 64 history, resets each request, threshold .5 / 64ms as benchmark. No text heuristics for silence.
- Run offline tests, full dev gates and device comparison plus actual integrated engine fixtures; human double-talk remains explicit pending until observed.

### Task 1: Adapter
- [ ] Add SenseVoice pinned resource loader/catalog, decoder with Silero admission and engine factory reusing current ownership infrastructure.
- [ ] Test missing/corrupt resources, silence skip, context/reset, lifecycle/cancellation integration without changing FireRed behavior.

### Task 2: Application wiring
- [ ] Wire Open config/provider/health to SenseVoice; preserve wake, cloud and frontend policies.
- [ ] Add assembly and integrated-engine instrumentation tests covering actual deployed factory, fixed public cases and no actions.
- [ ] Provision verified SenseVoice resources in production catalog location; build real vendor wake APK and install Open candidate.

### Task 3: Acceptance
- [ ] Independently review/fix code, run Android/Node and verified dev gates.
- [ ] Run full comparison and integrated factory fixture tests on device; inspect startup component health.
- [ ] Deliver review APK/report and request precise human acoustic test only after concrete installed result is ready.
