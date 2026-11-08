# Fixed FireRed CTC adapter

Uses the 23 unchanged Kotlin API files from sherpa-onnx v2.0.1, commit
`af2d8d81dbfa61c4cea4c568da78f3982edca005` (see third-party provenance/licenses).
CPU, one thread, greedy search; input is mono 16 kHz signed little-endian PCM16.
Every segment owns one finally-released stream. A process owner reuses one
recognizer across clients/mode handovers. All hashing, model construction,
inference and release run on that owner. Call synchronous `recognize` on the
existing candidate worker, never the UI/capture thread.

Bounds: one active plus two waiting requests, at most 30 seconds (960,000 bytes)
per segment. Overflow throws `RejectedExecutionException` and reports resource
pressure. An explicit owner-provided `FireRedAdmission` is mandatory; absent
facts deny decode. Deadline uses monotonic milliseconds. Finalized requests may
survive disabling new input; unfinished requests recheck their original permit
immediately before JNI. Close cancels the client's wait/output without waiting
for JNI; native release is serialized after running inference. Late load cannot
revive a closed/timed-out client. First model root is fixed for the process;
failed native release prevents another construction until process restart.

The packaged `src/main/resources/firered-model-catalog.json` fixes model,
tokens, runtime and attribution versions. External manifests must match it;
they cannot select another digest. Resources live under the App's own
`context.getExternalFilesDir("speech-models")/<modelId>/<exporterRevision>/`,
containing manifest.json, model.int8.onnx, tokens.txt and the three cataloged
license/attribution files under licenses/. The runtime validates every file
before creating the recognizer; loading a library alone never reports READY.
No runtime download, model APK payload or fake fallback exists.

Prepare an offline external directory from existing files at repository root:

```sh
python3 AutoVoice/tools/native/prepare_firered_resources.py \
  --model-source /path/to/fixed-model-cache \
  --attribution-source /path/to/fixed-attribution \
  --output-root /tmp/autovoice-firered-resources
```

The tool verifies copied bytes against the same catalog and atomically renames
its own temporary version directory. Existing versions are refused; retain them
and choose a separate output root. Development native files come from strict
staging with `-PnativeDevRoot=/tmp/autovoice-native-dev`. Release rejects that
property. Observed hashes remain unverified; trusted-lock requirements apply to
production artifacts.

## Production adapter Android test

FireRedProductInferenceTest uses the production loader/engine and real native
libraries/model, with controlled finalized test admission. Four small synthetic
Tingting public commands and an official wav are test-APK-only fixtures, not
human recordings or an accuracy benchmark. It checks one real final/established
per segment, strict NLU including negation unknown, one recognizer across all
segments, and rejection after close. It does not certify real Coordinator
admission, microphone/render integration or 8295 acoustics.

```sh
cd AutoVoice
./gradlew :adapter-firered:assembleDebugAndroidTest \
  -PnativeDevRoot=/tmp/autovoice-native-dev --offline
adb install -r adapter-firered/build/outputs/apk/androidTest/debug/adapter-firered-debug-androidTest.apk
adb shell am instrument -w -e class \
  'com.autovoice.adapterfirered.FireRedProductInferenceTest#prepareResourceDirectory' \
  com.autovoice.adapterfirered.test/androidx.test.runner.AndroidJUnitRunner
# After the app creates its own external directory:
adb push /tmp/autovoice-firered-resources/<catalog.modelId> \
  /sdcard/Android/data/com.autovoice.adapterfirered.test/files/speech-models/
adb shell am instrument -w -e class \
  'com.autovoice.adapterfirered.FireRedProductInferenceTest#fixedResourcesRealFinalAndStrictNlu' \
  com.autovoice.adapterfirered.test/androidx.test.runner.AndroidJUnitRunner
```

Missing resources fail with no assume/skip. Log tag: FireRedProductProbe. The
App uses its own package's model root. Task5 assembly deliberately provides no
admission snapshot and advertises localBridgeAvailable=false; Task6 binds real
Coordinator facts. Model/native READY and end-to-end local availability differ.

Shared lexical policy resides in :local-command-rules (depends only on core).
TextRuleNluEngine consumes actual finals; the old iFlytek provider delegates to
the leaf's unchanged legacy mapping. Open uses strict whole-utterance grammar:
existing window open/close and dialogue exit phrases only. Negation, quotes,
multiple/incidental commands and other text stay unknown. NLU never resubmits
PCM/text or emits a second transcript.
