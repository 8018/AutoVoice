# AutoVoice 集成验收记录（headless）— 2026-08-08

对应计划 Task 22 与 spec §9「集成验证（headless 部分）」。四剧本的真机部分见
`docs/runbook.md`（本次无设备、无 provider 密钥，不执行设备步骤）。

## 运行环境

- 无真机（`adb devices` 为空）；无 provider 密钥（XFYUN/DEEPSEEK/ALIYUN/DASHSCOPE 均未注入）。
- 因此本次验收 = 「JVM 单测 + mock/fake provider 端到端」全绿；真机链路留 `docs/runbook.md` 四剧本。

## 运行记录

### 1) 云端：`cd AutoVoiceServer && ./gradlew test`（含 EndToEndGatewayTest）

- 首次运行 task 判为 UP-TO-DATE，为取证以 `--rerun-tasks` 强制全量重跑：
  `./gradlew test --rerun-tasks` → **exit 0**
- 结果：**64 项测试，0 失败 / 0 错误 / 0 跳过**

| 模块 | 测试类 | 数 |
|---|---|---|
| app | EndToEndGatewayTest（mock providers 端到端）| 1 |
| app | FakeNluProviderTest | 2 |
| arbitration | RaceArbiterTest（nlu_first / nlu_rejected_use_llm / llm_first_wait_timeout / safety_timeout / 期限守卫）| 6 |
| session | SessionRegistryTest | 4 |
| nlu-traditional | IflytekNluProviderTest / IflytekSemanticNormalizerTest | 3+3 |
| llm | DeepSeekLlmProviderTest | 4 |
| asr-gateway | AliyunAsrProviderTest / AliyunTokenClientTest | 5+2 |
| tts-gateway | AliyunTtsProviderTest | 2 |
| gateway | GatewayCodecTest / VoiceGatewayHandlerTest / SegmentPipelineTest | 16+8+5 |
| contracts | ContractSmokeTest / IntentSerializationTest | 1+2 |

### 2) 端侧：`cd AutoVoice && ./gradlew test :app:assembleDebug`

- **exit 0**；共 **136 项测试执行，0 失败 / 0 错误 / 0 跳过**；`app-debug.apk` 产出
  （`app/build/outputs/apk/debug/app-debug.apk`，约 100MB，含 RNNoise 4 ABI so 与 ONNX 模型）。

| 模块 | 变体 | 测试执行数 |
|---|---|---|
| app（30 项）| testDebugUnitTest + testReleaseUnitTest | 30 + 30 |
| adapter-local（9 项）| debug + release | 9 + 9 |
| adapter-iflytek（12 项）| debug + release | 12 + 12 |
| voice-core（24 项）| JVM `test`（`--rerun-tasks` 强制重跑取证）| 24 |
| gateway-client（10 项）| JVM `test`（`--rerun-tasks` 强制重跑取证）| 10 |

（app/adapter 首次运行即重跑并产出 102 份新鲜 JUnit XML；voice-core/gateway-client
任务判 UP-TO-DATE，故单独 `--rerun-tasks` 强制重跑取证，均 exit 0。）

## 剧本覆盖映射

| 剧本 | headless 证据（本次运行） | 真机 |
|---|---|---|
| 1 断网本地兜底 | `VoiceEngineTest`「network unavailable → local only, cloud_unreachable, apply text spoken, cloud never ran」；`VoiceSessionTest`「onCloudUnavailable → local only」；`VoiceEngineTest`「cloud disabled in config → local only」 | 待真机（runbook 剧本 1）|
| 2 云端传统优先 | `EndToEndGatewayTest`：hello→ready→audio→decision(`nlu_first`)→reply(audio+speakText+intent climate/set_temperature+segmentId 回显)；`VoiceEngineTest`「cloud wins fast → player got AudioReply, vehicle applied intent, cloud_won logged」 | 待真机（runbook 剧本 2）|
| 3 云端 LLM 兜底 | `RaceArbiterTest`「llmFirstNluRejectedThenLlm」(`nlu_rejected_use_llm`) + `llm_first_wait_timeout`；`DeepSeekLlmProviderTest`；云端链路 mock 端到端由 `EndToEndGatewayTest` 覆盖。拒识→LLM 端到端未覆盖 | 待真机（runbook 剧本 3）|
| 4 云端超时用本地 | `VoiceEngineTest`「weakNetwork on → cloud delayed past cloudWaitMs → local wins with cloud_timeout_use_local」；`VoiceSessionTest`「cloud reachable but slow → Local winner, EXECUTING」 | 待真机（runbook 剧本 4）|

## 结论

- 服务端 64 项测试全绿；端侧 136 项测试执行全绿；debug APK 构建成功。
- 四剧本逻辑路径（端侧仲裁收敛、云端仲裁分支、消息契约、决策日志 reason）由
  JVM 单测与 mock provider 端到端钉住；**真实设备与真实 provider 链路未验证**，
  按 runbook 四剧本在真机补验。
