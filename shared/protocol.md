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
  | 二进制帧 | 客户端 → 服务端 | PCM 裸音频数据 | **S16LE（16-bit 有符号小端）、16000 Hz、单声道** |

- 音频二进制帧只允许出现在一次 `audio_start` 之后、`audio_end` 之前。建议每帧承载 20ms 音频（16000 Hz × 2 字节 × 0.02s = **640 字节**），便于流式 ASR 即时出中间结果。
- 文本帧一帧一条消息：不允许多条消息拼进同一帧，也不允许一条消息拆成多帧。

## 2. 消息总览

统一信封：`{"type": "<消息类型>", "payload": { ... }}`（`type` 必填，取值见下表）。

| type | 方向 | 作用 |
| --- | --- | --- |
| `hello` | 客户端 → 服务端 | 连接建立后的握手，声明客户端与协议版本 |
| `audio_start` | 客户端 → 服务端 | 声明一段录音流开始（采样率/声道/编码） |
| `audio_end` | 客户端 → 服务端 | 声明录音流结束（附时长） |
| `ready` | 服务端 → 客户端 | 握手成功，服务端就绪 |
| `decision` | 服务端 → 客户端 | 决策日志事件：本次请求由谁仲裁、走哪条路线及原因 |
| `asr_partial` | 服务端 → 客户端 | 云端 ASR 的中间识别结果（流式） |
| `reply` | 服务端 → 客户端 | 最终回复（文本 / 音频 / 动作意图） |
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
    "protocolVersion": "1.0",
    "sessionId": "demo-1"
  }
}
```

（与 `shared/fixtures/gateway-hello.json` 一致。）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `client` | string | 客户端标识，如 `autovoice-android` |
| `protocolVersion` | string | 协议版本，当前 `"1.0"` |
| `sessionId` | string | 会话 ID，本会话内所有消息复用 |

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
    "segmentId": "seg-1"
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

> **关联语义**：`segmentId` 由客户端生成、每轮话语唯一、不重复使用。服务端收到后记录为该话语的标识，
> 在随后的 `reply` 与 `error` payload 中原样回显（未携带时下行省略该字段）。客户端据此丢弃
> 上一轮的迟到 `reply` / `error`，避免串话。服务端侧字段：见 §4.4 / §4.5。

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

## 4. 服务端 → 客户端消息

### 4.1 ready

`hello` 校验通过后由服务端发送，表示网关就绪、可开始录音。

```json
{
  "type": "ready",
  "payload": {
    "sessionId": "demo-1",
    "language": "zh-CN",
    "protocolVersion": "1.0"
  }
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `sessionId` | string | 会话 ID |
| `language` | string | 识别与回复语言，如 `"zh-CN"` |
| `protocolVersion` | string | 服务端采纳的协议版本 |

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

云端 ASR 的流式中间结果，可在录音过程中持续下发（每帧一条）；`isFinal` 为 `true` 表示该 utterance 的最终识别文本。

```json
{
  "type": "asr_partial",
  "payload": {
    "sessionId": "demo-1",
    "text": "空调调到二十四",
    "isFinal": false
  }
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `sessionId` | string | 会话 ID |
| `text` | string | 当前已识别文本 |
| `isFinal` | boolean | 是否为最终结果 |

### 4.4 reply

最终的回复，按 `kind` 分三种形态：

- `text`：纯文本回复（可含 `speakText` 供 TTS 朗读）。
- `audio`：音频回复，携带 `mime` 与 `dataBase64`（网关下行主形态，另携带 `speakText` 与可选 `intent`）。
- `action`：动作意图回复，携带结构化 `intent`（可执行动作 + 槽位）与 `speakText`。

> **下行收敛（网关）**：云端网关对 `text` / `action` 回复统一合成音频并下行 **`kind=audio`**，
> payload 携带 `mime` / `dataBase64` / `speakText` / `intent`——`intent` 为 null 时省略字段（不发送 null），
> `speakText` 为 null 时同样省略；`audio` 形态（TTS 链路直接产物）则 `dataBase64` 直通。
> 音频超过 64KB 的 Base64 也一次消息下发，不分帧。
> **唯一例外（降级路径，spec §7.2）**：TTS 合成失败（或 ASR 失败兜底）时下行降级为 `kind=text`
> （仅 `speakText`，无 `text` / `mime` / `dataBase64` 字段），客户端按屏幕显示文本处理。

`reply/text`：

```json
{
  "type": "reply",
  "payload": {
    "kind": "text",
    "text": "已为您把空调调到24度",
    "speakText": "已为您把空调调到24度"
  }
}
```

`reply/audio`（与 `shared/fixtures/gateway-reply-audio.json` 一致）：

```json
{
  "type": "reply",
  "payload": {
    "kind": "audio",
    "mime": "audio/wav",
    "dataBase64": "UklGRiQAAABXQVZFZm10IBAAAAABAAEAQB8AAEAfAAABAAgAZGF0YQAAAAA=",
    "speakText": "已为您把空调调到24度",
    "segmentId": "seg-1"
  }
}
```

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
      "source": "nlu.iflytek.api"
    },
    "speakText": "已为您把空调调到24度",
    "segmentId": "seg-1"
  }
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `kind` | string（必填） | `text` / `audio` / `action` |
| `text` | string | 仅 `text`：回复文本 |
| `speakText` | string | 供 TTS 朗读的文本（`text` 与 `action` 必填；网关下行的 `audio` 亦携带，`audio` 形态本身无） |
| `mime` | string | 仅 `audio`：媒体类型，如 `audio/wav` |
| `dataBase64` | string | 仅 `audio`：音频数据的 Base64 编码 |
| `intent` | object | `action` 必填；网关下行的 `audio` 可选携带（为 null 时省略字段，不发送 null），字段见下 |
| `segmentId` | string（可选） | 回显对应 `audio_start` 携带的 `segmentId`（§3.2）；未携带时省略，三种 `kind` 均可带 |

`intent` 对象字段（与 `shared/contracts/intent.schema.json` 一致）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `schemaVersion` | string | 固定 `"1.0"` |
| `domain` | string | 领域，如 `climate` |
| `intent` | string | 意图名，如 `set_temperature` |
| `slots` | object | 槽位表：`{"<槽名>": {"type": "number|enum|string|boolean", "value": ..., "unit": "可选"}}` |
| `confidence` | number | 置信度，0 ~ 1 |
| `source` | string | 来源，如 `nlu.iflytek.api`、`llm.deepseek` |
| `rawSemantic` | string | 可选：上游原始语义 JSON 原文，用于排查 |

### 4.5 error

处理失败时发送，随消息附错误码与人类可读说明；发送 `error` 后连接由服务端关闭（或跟随 `bye`）。

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
| `code` | string | 机器可读错误码（如 `BAD_HELLO` / `ASR_FAILED` / `NLU_FAILED` / `LLM_FAILED` / `TTS_FAILED` / `INTERNAL`） |
| `message` | string | 人类可读错误说明 |
| `segmentId` | string（可选） | 回显当前话语的 `segmentId`（§3.2）；`audio_start` 未携带时省略。端侧据此丢弃他轮（上一轮）迟到的 `error` |

### 4.6 bye

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
     │ 7. 文本帧 decision（决策日志事件，必发）            │
     │ ◄───────────────────────────────────────────────── │
     │ 8. 文本帧 reply（text | audio | action）            │
     │ ◄───────────────────────────────────────────────── │
     │ 9. 文本帧 bye（服务端关闭连接）                     │
     │ ◄───────────────────────────────────────────────── │
```

要点：

1. **连接**：客户端发起 WS 连接 → 发 `hello`；服务端校验后回 `ready`。`ready` 之前客户端不得发送音频帧。
2. **录音段**：客户端发 `audio_start`（携带 PCM 参数）→ 连续发送二进制音频帧 → 发 `audio_end`。录音期间服务端可持续下发 `asr_partial`。
3. **结果**：服务端完成 ASR / 仲裁 / 回复生成后，先发 `decision`（日志事件，两端对齐），再发最终 `reply`，最后 `bye` 关闭连接。
4. 异常路径：任意阶段失败，服务端发 `error`（随后 `bye`）或直接 `bye`。

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

| reason | 含义 |
| --- | --- |
| `nlu_first` | NLU 优先：NLU 在等待窗口内先返回且命中，走传统 NLU（`nlu-traditional`） |
| `llm_first_wait_nlu_arrived` | LLM 先返回，等待窗口内 NLU 结果随后到达，最终采用 LLM（`llm`） |
| `nlu_rejected_use_llm` | NLU 先返回但被拒绝（置信度过低或意图不在白名单），改用 LLM（`llm`） |
| `llm_first_wait_timeout` | LLM 先返回，等待 NLU 超时，采用 LLM（`llm`） |
| `safety_timeout` | 安全超时兜底：整体处理超时，采用当时已产生的可用结果 |
| `asr_failed_fallback` | ASR 识别失败（或识别结果为空），走兜底话术，不合成音频（`cloud`） |
| `arbitration_failed_fallback` | 仲裁调用异常，走兜底话术，不合成音频（`cloud`） |

### 6.2 端侧仲裁（arbiter = `on-device`）的 reason 取值

| reason | 含义 |
| --- | --- |
| `cloud_won` | 云端结果先于本地结果到达且可用，采用云端（`cloud`） |
| `cloud_timeout_use_local` | 云端结果超时未到，采用本地结果（`local`） |
| `both_failed` | 两侧均失败，无可用结果（`route` 记录最后尝试的路线） |
| `cloud_unreachable` | 云端不可达（断网 / 握手失败），直接采用本地（`local`） |

> 与 `route` 的对应关系：`nlu_first` → `nlu-traditional`；`llm_first_*` / `nlu_rejected_use_llm` → `llm`；`cloud_won` → `cloud`；`cloud_timeout_use_local` / `cloud_unreachable` → `local`。

## 7. 消息字段与 schema / fixtures 的对应

- 消息信封、`type` 枚举与 payload 字段定义：`shared/contracts/gateway-messages.schema.json`。
- `reply` 中 `intent` 的结构：`shared/contracts/intent.schema.json`。
- 端侧与云端语音链路的配置项：`shared/contracts/config.schema.json`。
- 端云网关的 JSON 示例可直接复用 `shared/fixtures/gateway-*.json`；端云两侧的契约测试直接把这些 fixture 挂进 classpath（见两个模块的 `sourceSets.test.resources.srcDir("../../shared/fixtures")`）。
