# Qwen Omni S2S 架构与实施计划

更新时间：2026-08-16

## 1. 目标和约束

- 在线语音后端在构建时二选一：`classic`（ASR → DeepSeek）或 `omni`
  （qwen3.5-omni-plus）。同一个 Boot JAR 不携带两套实现。
- 同一份音频始终并发进入候选链路；仲裁只拦截输出，不控制音频是否进入模型。
- 保留两级独立仲裁，不引入三路总仲裁器。
- TTS/播放架构不拆除：文本继续请求 TTS 合成，S2S 音频交给同一个 TTS 播放模块。
- Skill Manager、MCP Registry、工具执行器和 system prompt 在两种后端间复用。

## 2. 两级仲裁

```text
端侧：车窗本地候选 ─┐
                     ├─ OnDeviceRaceArbiter → 端侧唯一输出
云端最终候选 ────────┘

云端：空调离线候选 ─┐
                     ├─ RaceArbiter → 云端唯一输出
在线语音候选 ────────┘
```

并发与优先级同时成立：

1. Android 同时执行车窗端侧识别和音频上传。
2. 服务端同时执行空调离线识别和选定的在线语音后端。
3. 云端空调命中时取消在线候选；否则放行在线候选。
4. 云端结果到达 Android 后仍需经过端侧仲裁；车窗命中时云端结果被拦截。
5. S2S 音频只有连续通过云端、端侧两道仲裁门后才能进入播放器。

## 3. 构建变体

```bash
./gradlew :app:bootJar -PvoiceBackend=classic
./gradlew :app:bootJar -PvoiceBackend=omni
```

- 公共代码只依赖 `OnlineSpeechProvider`。
- `src/classic/java` 装配 ASR、DeepSeek 和 `speech-classic`。
- `src/omni/java` 装配 `speech-qwen-omni`，并复用 `asr-gateway` 作为识别框旁路；
  ASR 不参与回答、工具调用或语义仲裁。
- CI 检查 Omni JAR 包含 `speech-qwen-omni.jar` 和旁路 `asr-gateway.jar`，但不包含
  `speech-classic.jar`、`llm.jar`。
- 部署工作流通过 `voice_backend` 输入或仓库变量 `VOICE_BACKEND` 选择构建变体。

密钥不进入构建产物，Omni 当前读取 `DASHSCOPE_API_KEY`。

同一份 PCM 会并发进入 Qwen 和旁路 ASR。Qwen 的 text modality 是“回答字幕”，不是用户原话；
旁路 ASR 通过独立 `asr_partial(text, isFinal)` 通道即时更新识别框，最终结果仍随
`audio_reply_end.asrText` 收口。ASR/PGS 不进入 NLU 仲裁门；旁路失败也不阻断 S2S 回答。
Qwen 提示词要求跟随用户当前语音语言回答，除非用户明确要求翻译。

端侧本地链显式拆为 `AsrStage` 与 `NluStage`：通用 ASR 的 partial/final 先上屏，再把最终
ASR 结果交给 NLU；当前讯飞 2C 命令词是“文本+语义同源”，其文本归入 `NluResult`，不能冒充
提前到达的 ASR。只有该 NLU 候选胜出时，才用其自带文本刷新识别框。

## 4. Omni 请求与工具复用

Omni 后端把 16kHz/mono/s16le PCM 封装为 WAV，通过 OpenAI-compatible
`chat/completions` 请求 qwen3.5-omni-plus：

- `stream=true`
- `modalities=[text,audio]`
- `audio.voice=Tina`
- `audio.format=wav`

按[千问官方模型说明](https://platform.qianwenai.com/docs/developer-guides/speech/s2s-models)，
该无后缀模型属于 HTTP 文件模式：当前实现是“整段输入、流式输出”，不是录音时同步上传给模型。
若后续要求输入也实时流式，应另立变体切换 `qwen3.5-omni-plus-realtime` WebSocket API；该实时
变体目前不支持 Function Calling，不能直接替换本方案的共享 Skills/MCP 工具循环。

SSE 解析器分别累计 text、audio 和按 index 分片的 tool call arguments。`audio.data` 按官方示例
作为一条跨 delta 的连续 Base64 串增量解码；输出裸 PCM 按 24kHz/mono/s16le 播放，兼容完整回复
时再封装 WAV。

工具来源与 Classic 相同：内置 `car_control`/`navigate` 加上
`McpSkillRegistry.enabledToolSpecs()`。MCP 调用复用 `McpToolExecutor`；平台 system prompt
复用 `SystemPromptStore`。车控/导航只生成 Intent，由 Android 执行一次；模型通过下一轮
生成最终确认语音。

## 5. 音频与 TTS

目标形态：

```text
文本回复 → TTS 合成入口 ─┐
                         ├─ TTS/SpeechOutput → 统一播放与打断
S2S 音频 → 音频输入入口 ─┘
```

第一阶段的完整 `AudioReply` 仍作为兼容/回归路径保留。第二阶段已经打通端到端流式会话：

1. `OnlineSpeechProvider` 增加 audio chunk 事件和可取消 session。
2. 服务端在空调离线仲裁完成前缓存 chunk；离线未命中后下发
   `audio_reply_start`、binary chunks、`audio_reply_end`。
3. Android 在车窗端侧仲裁完成前缓存云端 chunk；云端获胜后才交给 TTS。
4. `TtsPlayer` 增加基于 `AudioTrack` 的 24k PCM 流输入，同时保留现有完整 WAV/MP3 入口。
5. 任何一级高优先级候选胜出，都按 turn/segment ID 取消下游请求并清空对应缓冲。

文字展示与音频播放分离：

- 用户原话走 `asr_partial`，只校验 segment 是否仍有效，不等待云端或端侧语义仲裁。
- Qwen 每个 `delta.content` 形成累计 `reply_partial` 快照；服务端先经过云端空调仲裁门，
  Android 再缓存到端侧仲裁确认云端胜出，随后立即上屏，后续 delta 与音频播放同步更新。
- `reply` / `audio_reply_end` 只负责最终校正和收口，不再是第一次显示文字的时点。

## 6. 已完成和后续任务

| 阶段 | 内容 | 状态 |
| --- | --- | --- |
| P1 | `OnlineSpeechProvider` SPI、Classic 适配、现有云端仲裁回归 | 已完成 |
| P2 | Omni HTTP/SSE、WAV、MCP/终局工具循环、完整 AudioReply | 已完成 |
| P3 | Classic/Omni 构建隔离、CI 矩阵、部署选择 | 已完成 |
| P4 | 服务端流式会话与云端仲裁 chunk 门控 | 已完成（待真实环境验收） |
| P5 | Android 端侧仲裁 chunk 门控与 TTS AudioTrack 输入 | 已完成（待真机验收） |
| P6 | 同语言回答、旁路 ASR 识别框与协议下发 | 已完成（待真机验收） |
| P6.1 | ASR/NLU 拆分、PGS 独立显示、回复字幕随音频流式上屏 | 已完成（待真机验收） |
| P7 | 真实 DashScope、真机、弱网、取消和长音频验收 | 待外部环境 |

## 7. 验证重点

- 云端空调离线命中时，Omni future 和底层 OkHttp Call 被取消。
- 云端离线未命中时，流的 start/chunk/end 顺序稳定，并保留 speakText、asrText 和可选 Intent。
- 端侧车窗命中时不播放云端音频、不执行云端 Intent。
- ASR partial 在两级仲裁尚未收敛时仍能更新识别框；2C 文本仅在其 NLU 胜出后覆盖。
- 回复字幕在端侧云端候选胜出后、音频播放结束前持续更新；本地车窗胜出时不泄漏云端字幕。
- SSE 可处理任意 chunk 边界、多个 audio delta 和 tool arguments delta。
- Classic 与 Omni Boot JAR 依赖互斥。
- 真实 DashScope 与 Android 真机验收完成前，产品状态标注为“代码链路已流式、外部环境待验收”。
