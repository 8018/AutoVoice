# AutoVoice 端云网关协议（Gateway Protocol）

> 适用范围：端侧 AutoVoice（Android / Kotlin）与云端 AutoVoiceServer（Java）之间的实时语音交互。
>
> 权威性约定：消息的**结构与字段**以 `shared/contracts/gateway-messages.schema.json` 为准；字段的**语义、编解码与时序**以本文件为准。示例 JSON 与 `shared/fixtures/` 下的 fixture 文件保持一致（测试直接引用同一批文件）。

## 1. 传输层

- 传输协议：**WebSocket**（生产建议 wss://，本地 demo 可用 ws://）。
- 帧类型分两种，二者在一条连接上混合使用：

  | 帧类型 | 方向 | 内容 | 编码 |
  | --- | --- | --- | --- |
  | 文本帧 | 双向 | 一条 JSON 消息（统一信封 `{"type": "...", "payload": {...}}`） | UTF-8 |
  | 二进制帧 | 客户端 → 服务端 | 用户输入的 PCM 裸音频数据 | **S16LE（16-bit 有符号小端）、16000 Hz、单声道** |
  | 二进制帧 | 服务端 → 客户端 | S2S 模型输出的 PCM 音频块 | 参数由紧邻其前的 `audio_reply_start` 声明（当前为 S16LE、24000 Hz、单声道） |

- 音频二进制帧只允许出现在一次 `audio_start` 之后、`audio_end` 之前。建议每帧承载 20ms 音频（16000 Hz × 2 字节 × 0.02s = **640 字节**），便于流式 ASR 即时出中间结果。
- 文本帧一帧一条消息：不允许多条消息拼进同一帧，也不允许一条消息拆成多帧。

## 2. 消息总览

统一信封：`{"type": "<消息类型>", "payload": { ... }}`（`type` 必填，取值见下表）。

| type | 方向 | 作用 |
| --- | --- | --- |
| `hello` | 客户端 → 服务端 | 连接建立后的握手，声明客户端与协议版本 |
| `audio_start` | 客户端 → 服务端 | 声明一段录音流开始（采样率/声道/编码） |
| `audio_end` | 客户端 → 服务端 | 声明录音流结束（附时长） |
| `cancel_turn` | 客户端 → 服务端 | 端侧候选已胜出，取消该轮云端模型与后续下行 |
| `ready` | 服务端 → 客户端 | 握手成功，服务端就绪 |
| `decision` | 服务端 → 客户端 | 决策日志事件：本次请求由谁仲裁、走哪条路线及原因 |
| `asr_partial` | 服务端 → 客户端 | 云端 ASR 的中间识别结果（流式） |
| `reply_partial` | 服务端 → 客户端 | 模型回答文本累计快照，音频播放期间增量上屏 |
| `pending` | 服务端 → 客户端 | LLM 处理中占位：最终 `reply` 前的中间通知（可选，0..1 次） |
| `reply` | 服务端 → 客户端 | 最终回复（文本 / 动作意图；**TTS 解耦后不再携带音频**） |
| `audio_reply_start` | 服务端 → 客户端 | S2S 流式回复开始；后续二进制帧属于该回复 |
| `audio_reply_end` | 服务端 → 客户端 | S2S 流式回复结束，附最终文本/意图（若有） |
| `tts_request` | 客户端 → 服务端 | 独立 TTS 请求：按文本合成播报音频（§3.4） |
| `tts_response` | 服务端 → 客户端 | TTS 合成结果：音频数据（§4.6） |
| `error` | 服务端 → 客户端 | 错误通知 |
| `bye` | 服务端 → 客户端 | 服务端主动结束会话 |

## 3. 客户端 → 服务端消息

### 3.1 hello

连接建立后客户端发送的第一条消息。服务端未收到合法 `hello` 前不处理后续音频。

```json
{
  "type": "hello",
  "payload": {
    "client": "autovoice-android",
    "protocolVersion": "1.1",
    "sessionId": "demo-1"
  }
}
```

（与 `shared/fixtures/gateway-hello.json` 一致；示例中的 `sessionId` 仅为展示，客户端可不携带。）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `client` | string | 客户端标识，如 `autovoice-android` |
| `protocolVersion` | string | 协议版本，当前 `"1.1"`（v1.1：TTS 解耦——reply 不再携带音频，新增 `tts_request`/`tts_response`） |
| `sessionId` | string（可选） | 会话 ID，本会话内所有消息复用。**服务端权威**：客户端不预生成（首次连接可不携带，由服务端创建并在 `ready` 中回传采纳值）；重连时回带最近一次 `ready` 的值以恢复 Skills/MCP 上下文；携带时服务端优先采纳，未登记的会话自动创建 |
| `deviceId` | string（可选） | 设备标识（多设备加固 M1/M5）。网关 `auth-enabled` 时**必填**（与 `authToken` 一同校验），未启用鉴权时携带亦无副作用 |
| `authToken` | string（可选） | 设备令牌，与 `deviceId` 配对（服务器 `AUTOVOICE_GATEWAY_AUTH_DEVICES` 设备表）。鉴权失败 → `error(BAD_AUTH)` + 连接关闭（4001） |

#### 鉴权与连接准入（多设备加固）

网关可开启设备鉴权（`autovoice.gateway.auth-enabled`）：

- **鉴权开启**：`hello` 须携带合法 `deviceId` + `authToken`（与设备表精确匹配，常量时间比较）。
  失败 → `error`（code `BAD_AUTH`）+ `close(4001)`，**不注册会话**；
  成功 → 正常 `ready`，`deviceId` 记入服务端日志。
- **连接上限**（`max-connections`，默认 32）：超限新连接直接 `close(4001)`（不注册、不发 `error`）。
- 未开启鉴权时，老 hello（无凭据字段）行为不变。

### 3.2 audio_start

一段录音流开始前声明流参数。此后客户端持续发送二进制音频帧（PCM S16LE，16 kHz，单声道），直到 `audio_end`。

```json
{
  "type": "audio_start",
  "payload": {
    "sessionId": "demo-1",
    "sampleRate": 16000,
    "channels": 1,
    "encoding": "pcm_s16le",
    "segmentId": "seg-1",
    "latitude": 30.2741,
    "longitude": 120.1551
  }
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `sessionId` | string | 会话 ID |
| `sampleRate` | integer | 采样率，固定 `16000` |
| `channels` | integer | 声道数，固定 `1` |
| `encoding` | string | 编码，固定 `"pcm_s16le"` |
| `segmentId` | string（可选） | 每轮话语的唯一 ID（客户端生成，如 UUID）。demo 单连接多轮往返时，服务端无法凭 `sessionId` 区分话语，客户端需以此关联 `reply` / `error`（服务端原样回显） |
| `utteranceId` | string（可选） | 链路追踪 ID（端侧每轮 UUID）；服务端决策/插桩事件回带该值；缺省时服务端回退 `u-N` 自增 |
| `attempt` | integer（可选） | 同一 `utteranceId` 的发送次数，首次为 0；服务端按设备/会话 + `utteranceId` 幂等复用已完成结果 |
| `latitude` | number（可选） | 当前车辆纬度；定位授权且系统已有最近定位时发送，用于周边 POI 检索 |
| `longitude` | number（可选） | 当前车辆经度；与 `latitude` 成对发送；缺失时服务端退化为普通关键词检索 |

> **关联语义**：`segmentId` 由客户端生成、每轮话语唯一、不重复使用。服务端收到后记录为该话语的标识，
> 在随后的 `pending`、`reply` 与 `error` payload 中原样回显（未携带时下行省略该字段）。客户端据此丢弃
> 上一轮的迟到 `pending` / `reply` / `error`，避免串话。服务端侧字段：见 §4.4 / §4.5 / §4.8。

### 3.3 audio_end

一段录音流的结束标记。服务端收到后停止接收音频并开始产出结果。

```json
{
  "type": "audio_end",
  "payload": {
    "sessionId": "demo-1",
    "durationMs": 2340
  }
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `sessionId` | string | 会话 ID |
| `durationMs` | integer | 本段录音时长（毫秒） |

> **异步处理（多设备加固 M2）**：服务端收到 `audio_end` 后立即返回，识别/仲裁/回复在
> 连接专用线程异步进行（`decision` → `reply` 时序不变，见 §5）。**每连接同一时刻最多一段
> 话语处理中**：上一段尚未产出结果又收到新 `audio_end` → `error`（code `BUSY`，**不关闭
> 连接**），客户端可稍后重试或仅依赖本地兜底。处理期间的 `audio_start` 照常接受（累积新段）。

### 3.4 tts_request

**TTS 解耦（v1.1）**：播报音频由客户端按文本**另行请求**，与话语的识别/仲裁完全解耦——
设备收到 `reply` 后执行 `intent`（若为 action），再按回复文本发 `tts_request` 获取音频播放。
服务端合成失败走 `error`（code `TTS_FAILED`），**不关闭连接**。

**端侧缓存优先（架构变更）**：客户端发 `tts_request` 前先查**本地 TTS 缓存**（key =
播报文本，磁盘 `sha256(text).wav`）；命中直接播放**不发** `tts_request`（telemetry 记
`tts_cache_check` → `tts_cache_hit`），未命中才发送（记 `tts_cache_miss`），收到
`tts_response` 音频后写入缓存。服务端无缓存层，`tts_cache_*` 事件全部由客户端记录。

```json
{
  "type": "tts_request",
  "payload": {
    "text": "好的，空调已打开",
    "segmentId": "tts-1"
  }
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `text` | string（必填） | 待合成文本（UTF-8，即 reply 的 `speakText` / `text`） |
| `segmentId` | string（可选） | 客户端生成的对账 ID；非空时在 `tts_response` 与失败 `error` 中原样回显 |
| `utteranceId` | string（可选） | 透传到 TTS 服务的链路追踪 ID |

### 3.5 cancel_turn

端侧仲裁判定车窗等本地硬规则胜出后立即发送。服务端仅在 `segmentId` 与当前处理轮一致时取消
在线模型 future/HTTP call，并拦截该轮迟到的流式音频、`reply` 与 `error`；不影响同连接的下一轮。

```json
{
  "type": "cancel_turn",
  "payload": { "segmentId": "seg-1", "reason": "local_won" }
}
```

## 4. 服务端 → 客户端消息

### 4.1 ready

`hello` 校验通过后由服务端发送，表示网关就绪、可开始录音。

```json
{
  "type": "ready",
  "payload": {
    "sessionId": "demo-1",
    "language": "zh-CN",
    "protocolVersion": "1.0",
    "serverTime": 1786716679554
  }
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `sessionId` | string | 会话 ID |
| `language` | string | 识别与回复语言，如 `"zh-CN"` |
| `protocolVersion` | string | 服务端采纳的协议版本 |
| `serverTime` | number（可选） | 服务端墙钟毫秒（`System.currentTimeMillis()`）。客户端可据此估算设备与服务端的时钟偏移（offset ≈ serverTime + RTT/2 − 本地时刻），用于 telemetry 事件统一换算服务器时钟；旧客户端忽略该字段 |

### 4.2 decision（决策日志事件）

一段录音完成并经过仲裁后，服务端**必须**发送一条 `decision` 作为决策日志事件，客户端侧同样记录本会话的仲裁结果，保证两端日志一致。字段定义见第 6 节。

```json
{
  "type": "decision",
  "payload": {
    "arbiter": "on-device",
    "route": "local",
    "reason": "cloud_timeout_use_local",
    "utteranceId": "u-1",
    "timestampMs": 1723104000000
  }
}
```

### 4.3 asr_partial

云端 ASR 的流式中间结果，可在录音过程中持续下发（PGS 每帧一条）；`isFinal` 为 `true`
表示该 utterance 的最终识别文本。该消息独立于 NLU 和语义仲裁：只要属于当前 segment，
客户端就立即更新识别框。

```json
{
  "type": "asr_partial",
  "payload": {
    "segmentId": "seg-1",
    "text": "空调调到二十四",
    "isFinal": false
  }
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `segmentId` | string | 当前录音段 ID，用于丢弃上一轮迟到结果 |
| `text` | string | 当前已识别文本 |
| `isFinal` | boolean | 是否为最终结果 |

### 4.3.1 reply_partial

模型回答文本的累计快照。S2S 后端在解析 `delta.content` 时持续发送；服务端先经过云端
语义仲裁门，客户端再缓存至端侧仲裁确认云端胜出，随后与音频同步上屏，不必等待
`audio_reply_end`。最终 `reply`/`audio_reply_end.speakText` 仍可覆盖收口。

```json
{
  "type": "reply_partial",
  "payload": {
    "segmentId": "seg-1",
    "text": "Sure, I can help",
    "isFinal": false
  }
}
```

### 4.4 reply

最终的回复，按 `kind` 分两种形态（**TTS 解耦（v1.1）：下行只携带语义，不再携带音频**——
播报音频由客户端按回复文本另发 `tts_request` 获取，见 §3.4 / §4.6）：

- `action`：动作意图回复，携带结构化 `intent`（可执行动作 + 槽位）与 `speakText`。
- `text`：纯文本回复（闲聊 / 兜底 / 降级），**`text` 与 `speakText` 同带**（客户端对 `kind=text`
  强读 `text` 字段，缺失会丢回复）。

> **下行收敛（网关）**：云端网关按语义产出 `action` / `text` 两形态；`asrText`（识别文本，
> 离线命令命中时为离线原文，供客户端云端胜出时写入识别区）非空时附带；`segmentId`（§3.2）
> 非空时原样回显；`intent` 为 null 时省略字段（不发送 null）。**协议层不再下发 `kind=audio`**
> （音频见 `tts_response`；客户端对 `audio` 的解析保留为防御分支）。

`reply/action`（与 `shared/fixtures/gateway-reply-action.json` 一致）：

```json
{
  "type": "reply",
  "payload": {
    "kind": "action",
    "intent": {
      "schemaVersion": "1.0",
      "domain": "climate",
      "intent": "set_temperature",
      "slots": {
        "temperature": { "type": "number", "value": 24 },
        "zone": { "type": "enum", "value": "driver" }
      },
      "confidence": 0.95,
      "source": "llm.deepseek"
    },
    "speakText": "好的，空调温度已调到24度",
    "asrText": "把空调调到二十四度",
    "segmentId": "seg-1"
  }
}
```

`reply/text`：

```json
{
  "type": "reply",
  "payload": {
    "kind": "text",
    "text": "今天天气不错",
    "speakText": "今天天气不错",
    "asrText": "今天天气怎么样",
    "segmentId": "seg-1"
  }
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `kind` | string（必填） | `action` / `text` |
| `text` | string | 仅 `text`：回复文本（客户端强读；与 `speakText` 同带） |
| `speakText` | string | 供 TTS 朗读的文本（`action` 与 `text` 均必填） |
| `intent` | object | `action` 必填；字段见下 |
| `asrText` | string（可选） | 识别文本（ASR 结果；离线命令命中时为离线原文） |
| `segmentId` | string（可选） | 回显对应 `audio_start` 携带的 `segmentId`（§3.2）；未携带时省略 |

`intent` 对象字段（与 `shared/contracts/intent.schema.json` 一致）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `schemaVersion` | string | 固定 `"1.0"` |
| `domain` | string | 领域，如 `climate` |
| `intent` | string | 意图名，如 `set_temperature` |
| `slots` | object | 槽位表：`{"<槽名>": {"type": "number|enum|string|boolean", "value": ..., "unit": "可选"}}` |
| `slots` 特例 | string | 多目的地导航（先去A再去B）：`navigate` 意图加可选 `waypoints` 槽，type=string，value 为 `[{poiname,lat,lon}]` JSON 文本（SlotValue 无数组类型，数组 value 会被端侧 parseSlots 丢弃，故走 string 槽）；端侧据此拼 `amapuri://route/plan` 途经点参数（vian/vialons/vialats/vianames） |
| `confidence` | number | 置信度，0 ~ 1 |
| `source` | string | 来源，如 `nlu.iflytek.api`、`llm.deepseek` |
| `rawSemantic` | string | 可选：上游原始语义 JSON 原文，用于排查 |

### 4.5 error

处理失败时发送，随消息附错误码与人类可读说明。多数 `error` 发送后连接由服务端关闭（或跟随 `bye`）；
**例外：`TTS_FAILED`（§3.4 独立 TTS 链路失败）不关闭连接**，客户端可重试或稍后继续其他请求。

```json
{
  "type": "error",
  "payload": {
    "sessionId": "demo-1",
    "code": "ASR_FAILED",
    "message": "云端语音识别服务不可用",
    "segmentId": "seg-1"
  }
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `sessionId` | string | 会话 ID（已握手时） |
| `code` | string | 机器可读错误码（`BAD_HELLO` / `BAD_AUTH`（鉴权失败，随后 4001 关闭） / `BUSY`（上一段话语处理中，不关连接） / `ASR_FAILED` / `LLM_FAILED` / `TTS_FAILED` / `INTERNAL`） |
| `message` | string | 人类可读错误说明 |
| `segmentId` | string（可选） | 回显当前话语的 `segmentId`（§3.2，`tts_request` 失败时回显其 `segmentId`）；未携带时省略。端侧据此丢弃他轮（上一轮）迟到的 `error` |

### 4.6 tts_response

`tts_request` 的合成结果。音频数据随消息一次下发（不分帧）；服务端有缓存时命中直接回放
（本地日志 `TTS cache HIT`）。

```json
{
  "type": "tts_response",
  "payload": {
    "mime": "audio/wav",
    "dataBase64": "UklGRiQAAABXQVZFZm10IBAAAAABAAEAQB8AAEAfAAABAAgAZGF0YQAAAAA=",
    "text": "好的，空调已打开",
    "segmentId": "tts-1"
  }
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `mime` | string（必填） | 媒体类型，如 `audio/wav` |
| `dataBase64` | string（必填） | 音频数据的 Base64 编码 |
| `text` | string（可选） | 回显请求文本（客户端按话语对账） |
| `segmentId` | string（可选） | 回显 `tts_request` 携带的 `segmentId`（§3.4）；未携带时省略 |

### 4.7 bye

服务端主动结束会话（正常完成一轮对话、收到 `error` 后、或空闲超时）时发送，随后关闭连接。客户端不应再发送任何消息。

```json
{
  "type": "bye",
  "payload": {
    "sessionId": "demo-1",
    "reason": "session_complete"
  }
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `sessionId` | string | 会话 ID |
| `reason` | string | 结束原因，如 `session_complete` / `idle_timeout` / `error` |

### 4.8 pending（LLM 处理中占位）

LLM 工具循环（多轮工具调用）耗时可能超过端侧本地等待窗口，端侧会静默超时走本地兜底。
服务端在**离线命令未命中空调控制、且 LLM 尚未完成**时，先下发一条 `pending` 占位消息，告知端侧
"云端仍在处理中"，端侧据此**延长等待窗口**并显示处理中状态。

```json
{
  "type": "pending",
  "payload": {
    "segmentId": "seg-1",
    "text": "正在处理，请稍候"
  }
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `segmentId` | string（可选） | 回显 `audio_start` 携带的 `segmentId`（§3.2）；未携带时省略 |
| `text` | string（可选） | 处理中文案（端侧可忽略，使用自身固定文案） |

语义约束：

- **占位消息不等于最终结果**：不得替代 `reply`，`reply` 一定仍会到达（或 `error` / `bye`）。
- **至多一次**：每轮话语服务端至多下发一条 `pending`（离线回调仅触发一次，且 LLM 已完成时跳过）。
- **空调控制优先**：离线命令命中空调控制时直接胜出，**不下发** `pending`（后续 LLM 结果被拦截）。
- 端侧收到后仅延长等待窗口、更新 UI，**不执行、不播报**；迟到（本地已收敛）的 `pending` 按 §3.2 对账丢弃。

### 4.9 audio_reply_start / 二进制音频块 / audio_reply_end

该三段只由编译时选择的 S2S 后端产生。`audio_reply_start` 是可参与端侧仲裁的首个有效云端结果；
随后零到多个 WebSocket 二进制帧是同一 `segmentId` 的 PCM，最后以 `audio_reply_end` 收敛。
文本帧与二进制帧共用连接，因此同一连接同一时刻只允许一个活动的下行音频流。

```json
{
  "type": "audio_reply_start",
  "payload": {
    "segmentId": "seg-1",
    "mime": "audio/pcm",
    "sampleRate": 24000,
    "channels": 1,
    "encoding": "pcm_s16le"
  }
}
```

```json
{
  "type": "audio_reply_end",
  "payload": {
    "segmentId": "seg-1",
    "speakText": "Sure, the window is open.",
    "asrText": "Could you open the window?"
  }
}
```

`audio_reply_end.asrText` 是旁路 ASR 产生的用户原话最终快照，用于校正/收口；首次及中间
识别展示走不受仲裁阻塞的 `asr_partial`。
`audio_reply_end.intent` 可选，结构与 `reply/action.intent` 相同。音频不绕过 TTS 架构：端侧把 PCM
块交给 TTS 模块新增的流式音频入口，由其统一负责 AudioTrack 播放、停止和 telemetry；它不再做文本合成。

云端仲裁门先于 S2S **回答字幕、音频和语义**下行；`asr_partial` 是明确的例外。同一输入音频从
一开始便并发交给空调离线识别、旁路 ASR 和 S2S；空调离线命中则丢弃缓存的 S2S 回答并取消在线
请求，但不拦截 ASR 展示。未命中/失败才按原顺序放行 S2S 回答。这里的“未命中才放行”不是
“未命中才上传/调用模型”。

## 5. 时序（连接 → 录音段 → 结果）

一轮完整对话（单段录音）：

```
客户端 (AutoVoice)                                  服务端 (AutoVoiceServer)
     │                                                    │
     │ 1. 建立 WebSocket 连接                             │
     │ ─────────────────────────────────────────────────► │
     │ 2. 文本帧 hello                                    │
     │ ─────────────────────────────────────────────────► │
     │ 3. 文本帧 ready                                    │
     │ ◄───────────────────────────────────────────────── │
     │ 4. 文本帧 audio_start                              │
     │ ─────────────────────────────────────────────────► │
     │ 5. 二进制帧 PCM（S16LE / 16 kHz / 单声道，可多帧）  │
     │ ─────────────────────────────────────────────────► │
     │      （并行）文本帧 asr_partial（0..n 次）          │
     │ ◄───────────────────────────────────────────────── │
     │ 6. 文本帧 audio_end                                │
     │ ─────────────────────────────────────────────────► │
     │ 6a. 文本帧 pending（LLM 处理中占位，可选，0..1 次）  │
     │ ◄───────────────────────────────────────────────── │
     │ 7. 文本帧 decision（决策日志事件，必发）            │
     │ ◄───────────────────────────────────────────────── │
     │ 8. 文本帧 reply（action | text，无音频）            │
     │ ◄───────────────────────────────────────────────── │
     │ 9. 文本帧 bye（服务端关闭连接）                     │
     │ ◄───────────────────────────────────────────────── │
```

S2S 编译变体的结果阶段替换为：

```
同一份输入音频 ─┬─► 云端空调离线识别 ─┐
                 └─► S2S 模型流式生成 ──┴─► 云端仲裁门（空调离线优先）
                                                   │ 未命中才放行输出
客户端本地车窗识别 ────────────────────────────────┼─► 端侧仲裁门（车窗本地优先）
                                                   │ 云端胜出
                                                   ▼
                         audio_reply_start → 二进制 PCM(0..n) → audio_reply_end
                                                   │
                                                   ▼
                                      TTS 模块 AudioTrack 播放入口
```

要点：

1. **连接**：客户端发起 WS 连接 → 发 `hello`；服务端校验后回 `ready`。`ready` 之前客户端不得发送音频帧。
2. **录音段**：客户端发 `audio_start`（携带 PCM 参数）→ 连续发送二进制音频帧 → 发 `audio_end`。录音期间服务端可持续下发 `asr_partial`。
3. **结果**：服务端完成 ASR / 仲裁 / 回复生成后，先发 `decision`（日志事件，两端对齐），再发最终 `reply`，最后 `bye` 关闭连接。可选步骤：离线命令未命中空调控制且 LLM 尚未完成时，在 `decision` 前先发 `pending`（LLM 处理中占位，至多一次），端侧据此延长等待窗口（§4.8）。
4. **TTS 解耦（v1.1）**：设备收到 `reply` 后（action 先执行 `intent`），按 `speakText` 另发
   `tts_request` 获取播报音频——该请求与话语的识别/仲裁**完全独立**，可随时发起（任意轮之间）：
   `tts_request` → `tts_response`；合成失败 → `error`（`TTS_FAILED`，不关连接）。
5. 异常路径：任意阶段失败，服务端发 `error`（随后 `bye`）或直接 `bye`（`TTS_FAILED` 除外，见 §4.5）。
6. **两级仲裁彼此独立**：云端只比较“空调离线 vs 在线模型”；端侧只比较“本地车窗 vs 云端最终候选”。两边候选均从录音开始并发计算，仲裁只拦截输出。端侧本地胜出后发送 `cancel_turn`，避免继续消耗在线资源。

### 5.1 链路追踪（telemetry）

链路追踪与 WS 协议消息**独立**：端侧每轮事件（`utterance_start` / `vad` / `local_asr` /
`device_arbiter` / `device_arbiter_pending` / `execute` / `tts_request` / `tts_play`）经 HTTP `POST /api/telemetry/round`
上报（**非 WS 协议消息**）；服务端插桩事件（`cloud_asr` / `offline_pool` / `llm` /
`cloud_arbiter` / `cloud_arbiter_pending` / `tts_request` / `tts_cache` / `tts_synth`）落同库；查询 API 与面板地址
`/telemetry/`。上报为**尽力而为**：失败或禁用不影响业务。

## 6. 决策日志事件（decision）规范

`decision` 是**日志事件**而非业务结果：它记录“本次请求由谁仲裁（`arbiter`）、最终走了哪条路线（`route`）、为什么（`reason`）”，用于两端日志对账与调优。

```json
{
  "type": "decision",
  "payload": {
    "arbiter": "on-device",
    "route": "local",
    "reason": "cloud_timeout_use_local",
    "utteranceId": "u-1",
    "timestampMs": 1723104000000
  }
}
```

| 字段 | 类型 | 取值 | 说明 |
| --- | --- | --- | --- |
| `arbiter` | string | `on-device` / `cloud` | 由谁完成最终仲裁（端侧本地仲裁 or 云端仲裁） |
| `route` | string | `local` / `cloud` / `nlu-traditional` / `llm` | 最终采用的回复路线 |
| `reason` | string | 见下表 | 作出该决策的原因 |
| `utteranceId` | string | 任意字符串 | 本次话语（请求）的唯一 ID |
| `timestampMs` | integer | 毫秒时间戳 | 决策发生时刻 |

### 6.1 云端仲裁（arbiter = `cloud`）的 reason 取值

云端链路为**双候选竞速**：离线命令识别（route `nlu-traditional`）与 ASR→LLM（route `llm`）并行，
离线命中立即胜出；LLM 到达后起 1.5s 宽限期（`offline-grace-ms`）等离线——宽限期内离线命中则
离线胜出，到点离线无结果则 LLM 胜出（离线已完成时 LLM 立即胜出）。

| reason | 含义 |
| --- | --- |
| `offline_won` | 离线命令命中（规则映射非 unknown）立即胜出，走传统链路（`nlu-traditional`） |
| `llm_reply` | LLM 胜出：离线未命中 / 宽限期超时 / 离线已完成时 LLM 到达（`llm`） |
| `safety_timeout` | 安全超时兜底：整体处理超时，采用当时已产生的可用结果 |
| `asr_failed_fallback` | ASR 识别失败（或识别结果为空）且离线窗口内无命中，走兜底话术（`cloud`） |
| `arbitration_failed_fallback` | 仲裁调用异常，走兜底话术（`cloud`） |

### 6.2 端侧仲裁（arbiter = `on-device`）的 reason 取值

| reason | 含义 |
| --- | --- |
| `cloud_won` | 云端结果先于本地结果到达且可用，采用云端（`cloud`） |
| `cloud_timeout_use_local` | 云端结果超时未到，采用本地结果（`local`） |
| `both_failed` | 两侧均失败，无可用结果（`route` 记录最后尝试的路线） |
| `cloud_unreachable` | 云端不可达（断网 / 握手失败），直接采用本地（`local`） |

> 与 `route` 的对应关系：`offline_won` → `nlu-traditional`；`llm_reply` → `llm`；`cloud_won` → `cloud`；`cloud_timeout_use_local` / `cloud_unreachable` → `local`。

## 7. 消息字段与 schema / fixtures 的对应

- 消息信封、`type` 枚举与 payload 字段定义：`shared/contracts/gateway-messages.schema.json`。
- `reply` 中 `intent` 的结构：`shared/contracts/intent.schema.json`。
- 端侧与云端语音链路的配置项：`shared/contracts/config.schema.json`。
- 端云网关的 JSON 示例可直接复用 `shared/fixtures/gateway-*.json`；端云两侧的契约测试直接把这些 fixture 挂进 classpath（见两个模块的 `sourceSets.test.resources.srcDir("../../shared/fixtures")`）。
