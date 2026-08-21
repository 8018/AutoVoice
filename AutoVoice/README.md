# AutoVoice（Android 端）

周末 Demo 里程碑 ① 的端侧语音车控客户端：本地链路（VAD → RNNoise 降噪 → 离线/命令词
ASR → 规则 NLU）与云端链路（WS 网关 → 云端传统 NLU/LLM）**并发竞速**，由端侧仲裁
（`cloudWaitMs=2000ms` 兜底本地）收敛，再经 mock 车控执行 + TTS 播报。

- 设计：`docs/specs/2026-08-08-autovoice-demo-design.md`
- **验收手册（真机四剧本 + 前置条件 + 常见问题）：`docs/runbook.md`**
- Headless 自动化验收记录：仓库根 `ACCEPTANCE.md`

## 项目结构

```
AutoVoice/                      Android 端（Kotlin）
├── voice-core/                 消息模型（gateway 消息 schema）、DemoConfig、
│                               Stage SPI、VoiceSession 状态机、OnDeviceRaceArbiter
├── gateway-client/             WS 网关客户端（连接/重连、事件桥、segmentId 对账）
├── adapter-local/              Silero VAD（ONNX）+ RNNoise 降噪（JNI，4 ABI）
├── adapter-iflytek/            讯飞离线唤醒 IVW + 离线命令词 AIKit（共享运行时）+
│                               FakeCommandAsr + RuleNlu（规则意图映射表）
└── app/                        Compose UI（决策日志/模拟车控/录音）、AudioRecorder、
                                TtsPlayer（WAV）/SystemTtsFallback（TextToSpeech）、
                                VoiceEngine 装配接线、demo-full/demo-offline 配置资产

AutoVoiceServer/                云端（Java 21 / Spring Boot）——见 ../docs/runbook.md 约定
├── contracts/                  端云共享契约（Intent / Reply / Asr|Nlu|Llm|Tts Provider SPI）
├── arbitration/                RaceArbiter（1500ms NLU 宽限 + LLM 兜底 + safety 期限）
├── session/                    SessionRegistry（会话管理）
├── nlu-traditional/            讯飞语义 API + Canonical Intent 归一化
├── llm/                        DeepSeek LLM
├── asr-gateway/ tts-gateway/   阿里云 ASR / TTS（NLS）
├── gateway/                    GatewayCodec / SegmentPipeline / VoiceGatewayHandler（WS /ws）
└── app/                        Spring Boot 装配（demo-full profile，端口 8080）

shared/                         端云共享契约（双项目校验）
├── protocol.md                 消息协议（hello/ready/audio_start/decision/reply…）
└── contracts/ + fixtures/      JSON Schema + 各 provider 应答 fixture

docs/                           spec 设计与验收手册（runbook.md）
```

## 运行方式

真机验收（前置条件、服务端/端侧启动、四剧本操作与期望、常见问题）**全部见
[`docs/runbook.md`](../docs/runbook.md)**。摘要：

```bash
# 1. 开发机：导出密钥后启动云端服务端
cd AutoVoiceServer
export XFYUN_APPID=... XFYUN_API_KEY=... DEEPSEEK_API_KEY=... \
       ALIYUN_AK=... ALIYUN_SK=... ALIYUN_NLS_APPKEY=... DASHSCOPE_API_KEY=...
./gradlew :app:bootRun --args='--spring.profiles.active=demo-full'

# 2. 手机：装 App（同一局域网），并先把 demo-full.json 的 gatewayUrl 改成开发机 LAN IP
cd AutoVoice
./gradlew :app:installDebug
```

### 离线唤醒

前台运行且取得录音权限后，App 默认监听“你好飞飞”。唤醒与语音链共享唯一
`AudioRecord`：待机 IVW 直接消费原始 PCM，唤醒后才启用 Silero VAD/RNNoise 分支，
VAD `SpeechEnd` 自动提交本轮。退到后台会停止麦克风，若要
后台常驻唤醒需另行改成 Android 前台服务。

真机 SDK 需把两个交付包合为一个 AAR（Java API 相同，保留两个 native ability 插件）：

```bash
cd AutoVoice
tools/prepare-iflytek-aikit.sh \
  <离线命令词SDK>/demo/app/libs/AIKit.aar \
  <离线唤醒SDK>/demo/app/libs/AIKit.aar
adb push <离线唤醒SDK>/resource/ivw /sdcard/iflytek/
```

凭据继续由根目录 `local.properties` 的 `xfyun.appid`、`xfyun.apiKey`、
`xfyun.apiSecret` 注入；缺凭据、资源或合并 AAR 时，界面会显示唤醒不可用。

## 验收结果表

| 剧本 | 状态 | 备注（headless 覆盖证据） |
|---|---|---|
| 1 断网本地兜底 | **待真机验收**（headless 已覆盖） | 本地链路 + `cloud_unreachable` 由 JVM 单测覆盖：`VoiceEngineTest`「network unavailable → local only，apply text spoken，cloud never ran」、`VoiceSessionTest`「onCloudUnavailable → local only」等（2026-08-08 headless 运行全绿） |
| 2 云端传统优先 | **待真机验收**（headless 已覆盖） | 云端链路（mock providers）由服务端 `EndToEndGatewayTest` 端到端覆盖：hello→audio→ASR→NLU→decision `nlu_first`→reply（audio + speakText + intent climate/set_temperature + segmentId 回显）；端侧 `cloud_won` 由 `VoiceEngineTest`「cloud wins fast → player got AudioReply, vehicle applied」覆盖 |
| 3 云端 LLM 兜底 | **待真机验收**（headless 部分覆盖） | 云端链路（mock providers）由 `EndToEndGatewayTest` 覆盖；拒识→LLM 分支由 `RaceArbiterTest`（`nlu_rejected_use_llm` / `llm_first_wait_timeout`）+ `DeepSeekLlmProviderTest` 单测钉住；**拒识→LLM 的端到端路径未覆盖**，需真机 |
| 4 云端超时用本地 | **待真机验收**（headless 已覆盖） | `cloud_timeout_use_local` 由 `VoiceEngineTest`「weakNetwork on → cloud delayed past cloudWaitMs → local wins」、`VoiceSessionTest`「cloud reachable but slow → Local winner, EXECUTING」覆盖 |

> **诚实标注**：四个剧本的 headless 覆盖以 JVM 单测 + mock/fake provider 为限——
> 真实麦克风收音 → RNNoise 降噪 → 真实讯飞/阿里云 API → 真实授权流程 → 真实 TTS
> 播报均**未验证**，故四剧本状态全部为**待真机验收**，headless 全绿不构成真机通过证明。
> 完整 headless 运行记录（命令、退出码、测试总数）见仓库根 `ACCEPTANCE.md`。

## 本地快速自检（headless）

```bash
cd AutoVoiceServer && ./gradlew test                       # 云端 64 项测试（含 EndToEndGatewayTest）
cd AutoVoice && ./gradlew test :app:assembleDebug          # 端侧全部单测（debug/release 变体）+ APK
```
