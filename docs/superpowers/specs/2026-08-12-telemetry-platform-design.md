# 链路数据平台（Telemetry Platform）设计

## 背景与目标

AutoVoice 目前链路（话语开始 → VAD → 本地识别 → 云端识别 → LLM → 双端仲裁 → 执行 → TTS → 播放）各阶段只有 logcat/console 文本日志，无结构化事件、无上报通道、无关联键贯通（服务端 `u-N`、云端仲裁填 sessionId、端侧仲裁填空串——三个键对不上）。目标：建一个链路数据平台，**追踪从话语开始到 TTS 播放结束的完整链路**——每阶段结果、耗时、失败、VAD 后语音可回放、TTS 缓存命中、播放结果，并实时可视化。

用户确认的目标形态（分阶段全做）：数据通道 → 查询分析 → **框架级实时面板**（Vite + React + SSE）。

## 架构总览

```
┌─ 设备 App（Android）─────────────────────────────┐
│  话语开始 → VAD → 本地识别 → 端云仲裁 → 执行 → TTS 播放 │
│    每阶段插桩，事件收进"本轮事件包"                     │
└──┬──────────────────────┬───────────────────────┘
   │ WS /ws（现有，业务不动）│ 每轮结束后 HTTP POST
   │                      │   /api/telemetry/round
   │                      │   {utteranceId, sessionId, deviceId, events[]}
   │                      │ 若有音频：POST /api/telemetry/audio（multipart）
   ▼                      ▼
┌─ 网关 AutoVoiceServer:8080（同进程、同 Tomcat）──────┐
│  telemetry 模块（新 Gradle 子模块）                     │
│  ├─ 收端侧事件包 → SQLite（单文件，默认保留 7 天）         │
│  ├─ PCM 加 44B WAV 头落盘 /opt/autovoice/telemetry/audio│
│  ├─ 服务端插桩（ASR/离线池/LLM/仲裁）→ 同库               │
│  ├─ 查询 API + SSE 流 + 静态面板（jar 内 resources）      │
└──────────────────────────────────────────────────┘
        ▲
        │ tts-server 事件经 HTTP 转发（不跨进程写库）
        │
┌─ tts-server:8082（独立进程，M4）────────────────────┐
│  插桩：tts_request / tts_cache(HIT|MISS) / tts_synth   │
└──────────────────────────────────────────────────┘
```

决策：
- **不新建独立进程**：telemetry 与网关同进程同端口（Spring Boot HTTP 与 WS 共存）；演进路径：网关多实例时 telemetry 拆独立服务，端侧上报地址一改即可。
- **业务 WS 协议不动**：telemetry 走独立 HTTP 端点，与 WS 生命周期解耦（WS 断线不丢本轮端侧事件）。
- **tts-server 是独立进程**，其事件经 HTTP 转发到网关 telemetry API（`autovoice.telemetry.url` 配置，默认空=关闭），避免跨进程写同一 SQLite 文件。

## 事件模型

### utteranceId 全链路打通（核心）

- 端侧 `VoiceEngine.onListeningStart()`（话语/会话开始）**生成 utteranceId（UUID）**，一轮一个，贯穿端侧全部插桩。
- 随现有 `audio_start` payload 上传网关（`audio_start` 已带 segmentId；新增 utteranceId 可选字段，向后兼容）。服务端 `SegmentContext` 加 utteranceId 字段，ASR/离线池/LLM/仲裁/TTS 转发全链路复用。
- 修正两处仲裁器的 utteranceId 填值（字段已存在）：
  - 服务端 `RaceArbiter.entry()`：填真实 utteranceId（当前填 sessionId）。
  - 端侧 `OnDeviceRaceArbiter`：填真实 utteranceId（当前填 ""）。
- `DecisionEntry` 经现有 sink 透传，utteranceId 修正后端云决策事件即可对账。

### 存储（SQLite 两张表）

```
rounds 表（每轮一行，聚合视图）
  utterance_id TEXT PK | session_id | device_id | source(button/auto)
  start_ms | end_ms | local_decision | cloud_decision | final_decision
  asr_local | asr_cloud | llm_reply | execute_result
  tts_text | tts_cache_hit | playback_result | audio_path

events 表（每阶段一行，时间线明细）
  id INTEGER PK AUTOINCREMENT | utterance_id | stage TEXT
  ts_ms | level(info/warn/error) | payload_json

stage 枚举：utterance_start / vad / local_asr / cloud_asr / llm / offline_pool
           / cloud_arbiter / device_arbiter / execute / tts_request
           / tts_cache / tts_synth / tts_play
```

- `vad` 事件 payload：段数、总时长、maxProb、VAD 事件数、块数；PCM 单独存储（见下）。
- `tts_cache` 事件 payload：`hit`(true/false)、bytes；`tts_synth`：bytes、耗时、失败信息；`tts_play`：source(network/system)、bytes、结果(ok/failed/interrupted)。

### PCM 音频存储

- 端侧 VAD 后的语音段（每轮一段 PCM s16le 16k mono）经 `POST /api/telemetry/audio`（multipart：utteranceId + pcm）上传。
- telemetry 模块加 44 字节标准 WAV 头落盘 `{audioDir}/{utteranceId}.wav`；`rounds.audio_path` 记录相对路径。
- 面板经 `GET /api/telemetry/audio/{file}` 回放下载。
- 取舍注明：真断网（飞行模式）轮次无通道不上报；WS 活着但云端超时的本地兜底轮次正常上报。

## 端侧插桩（AutoVoice/app + voice-core + adapter-*）

每阶段产生事件收进本轮事件包，轮次结束（onTurnResult / 停止录音后）批量 `POST /api/telemetry/round`；事件包结构 `{utteranceId, sessionId, deviceId, startMs, endMs, events:[{stage, tsMs, level, payload}]}`。

| 阶段 | 插桩点 | 事件内容 |
|---|---|---|
| utterance_start | `VoiceEngine.onListeningStart()`（:145）——**生成 utteranceId** | source(button/auto)、ts |
| vad | `AudioRecorder.finishSegments()`（:169 现有聚合日志处） | 段统计（段数/总时长/maxProb/事件数）；PCM 另路上传 |
| local_asr | `VoiceEngine.kt:339,342`（现有日志处） | text、intent、source(fake/iflytek/rule)、耗时 |
| device_arbiter | `OnDeviceRaceArbiter` sink（:49/55/59）——填真实 utteranceId | route、reason |
| execute | `VoiceEngine.onTurnResult()`（:167/226） | intent、车辆状态、播报文本、成功/兜底 |
| tts_request | `VoiceEngine.speakViaTts()`（:207-212） | 输入文本 |
| tts_play | `TtsPlayer`（:139/143/149/166）+ `SystemTtsFallback`（补插桩） | source、bytes、ok/failed/interrupted |

端侧配置：`demo-full.json` 加 `telemetry: {enabled: true, url: ""}`（url 空时从 gatewayUrl 推导 `ws://h:p/ws` → `http://h:p`）；demo-offline 关闭。telemetry 不可用时静默跳过（不阻塞业务）。

## 服务端插桩（AutoVoiceServer）

`TelemetryRecorder` 接口放 **:contracts**（与现有 `DecisionSink` 同模式）；各插桩点注入该接口，运行时由 telemetry 模块提供实现；未启用时装配 Noop（与 `offlineCommandService` try/catch → Noop 模式一致）。

| 阶段 | 插桩点 | 事件内容 |
|---|---|---|
| cloud_asr | `SegmentPipeline.java:109-121`（现有日志处） | text、耗时、空/失败 |
| offline_pool | `OfflineEnginePool`（:44/52/60/66） | busy/skip/失败 |
| llm | `DeepSeekLlmProvider`（全静默，补插桩） | text/action、耗时、失败 |
| cloud_arbiter | `RaceArbiter.decide()`（:79/90/97/107）——填真实 utteranceId | route、reason |
| tts 转发 | `RemoteTtsProvider`：内部协议加 utteranceId（`{text, sessionId}` → 加 utteranceId） | 转发关联键 |

tts-server（独立进程）插桩：`TtsController` / `CachedTtsProvider`（:62/72 现有日志处）/ `AliyunTtsProvider`（补）——事件（stage=tts_request/tts_cache/tts_synth）经 HTTP `POST` 网关 telemetry API 转发（配置 `autovoice.telemetry.url`，默认关闭；事件带 utteranceId、text、hit、bytes、耗时、失败）。

## telemetry 模块（AutoVoiceServer 新子模块）

- 依赖：`:gateway :contracts` + spring-web + `org.xerial:sqlite-jdbc`。
- `TelemetryService`：单线程 executor 串行写库；`@Scheduled` 每天清理超期轮次（retention 默认 7 天，rounds+events+音频文件联动删除）。
- `TelemetryController`：
  - `POST /api/telemetry/round` 收端侧事件包
  - `POST /api/telemetry/audio` 收 PCM（加 WAV 头落盘）
  - `GET /api/telemetry/rounds?device=&from=&to=` 列表 + 统计摘要
  - `GET /api/telemetry/rounds/{utteranceId}` 单轮全事件
  - `GET /api/telemetry/stream` SSE（SseEmitter，新轮次入库即推 round 摘要）
  - `GET /api/telemetry/audio/{file}` 回放
- 配置（application.yml，env 可覆盖）：`autovoice.telemetry.db-path`、`audio-dir`、`retention-days`（默认 7）、`enabled`（默认 true；false 时 recorder=Noop，端侧上报 404/忽略）。
- 面板静态资源：`telemetry-web` 构建产物 copy 进 telemetry 模块 `src/main/resources/static/telemetry/`，`http://47.94.4.204:8080/telemetry/` 访问（8080 同端口）。

## 实时面板（AutoVoiceServer/telemetry-web/，Vite + React + TS）

- 工具链：Vite + React + TypeScript + 轻量组件（不引入重型 UI 库，手写样式保持轻）。
- **轮次列表**：SSE 实时追加新轮次（失败标红、缓存命中绿标），按设备/时间筛选。
- **单轮时间线**：每阶段条带（阶段/耗时/结果），端云事件按 utteranceId 汇合；失败红、缓存命中绿标。
- **音频回放**：VAD 后语音 WAV 可播。
- **统计视图**：平均端到端耗时、各阶段平均耗时、失败率、TTS 缓存命中率、决策分布（cloud_won/local/unknown 占比）。
- 构建：`npm run build` → 产物进 telemetry jar；本地开发 dev server 代理 `/api` 到 8080。

## 部署

- 服务器 .env 追加（默认值可省）：`AUTOVOICE_TELEMETRY_DB=/opt/autovoice/telemetry/telemetry.db`、`AUTOVOICE_TELEMETRY_AUDIO_DIR=/opt/autovoice/telemetry/audio`、`AUTOVOICE_TELEMETRY_RETENTION_DAYS=7`；tts-server env `AUTOVOICE_TELEMETRY_URL=http://127.0.0.1:8080/api/telemetry`。
- 端侧 `demo-full.json`：`telemetry: {enabled: true}`。
- 面板访问：`http://47.94.4.204:8080/telemetry/`。

## 测试计划

- telemetry 模块单测：round 入库、查询过滤、7 天清理（含音频联动删除）、SSE 推送、音频 WAV 头存取。
- 端侧单测：事件包构造、utteranceId 贯通（`GatewayClientTest` 扩：audio_start 带 utteranceId）。
- E2E：`MultiDeviceGatewayTest` 扩展——双连接各自完整 round，端侧事件包 + 服务端插桩事件按 utteranceId 汇合断言。
- 面板：构建通过 + 浏览器手测（真机轮次出现/回放/标红/统计）。
- 回归：两端现有测试全绿（协议向后兼容，无行为变更）。

## 风险与取舍

- **端侧 PCM 上传流量**：每轮 16k/16bit ≈ 30-60KB（1-2s 语音），demo 量级可接受；后续可压缩/抽帧。
- **SQLite 单线程写**：每轮写入次数个位，无压力；多实例演进时拆独立服务。
- **SSE 连接**：单面板一连接，无压力。
- **插桩侵入面**：插桩点全是现有日志行附近加一行 recorder 调用，业务逻辑不变；recorder=Noop 时零影响。
- **tts-server 事件转发**：转发失败（网关挂）仅丢 TTS 侧事件，不影响合成服务本身。

## 实施顺序

1. `:contracts` 加 `TelemetryRecorder` 接口 + utteranceId 贯通（audio_start 字段、SegmentContext、两仲裁器填值修正）
2. telemetry 模块（存储/Controller/SSE/清理/音频）
3. 服务端插桩（ASR/离线池/LLM/仲裁/TTS 转发 + tts-server 事件转发）
4. 端侧插桩（utteranceId 生成、事件包、音频上传、7 个阶段事件点）
5. telemetry-web 面板（列表/时间线/回放/统计）
6. 测试与部署（单测/E2E/构建/服务器部署/真机验证）
