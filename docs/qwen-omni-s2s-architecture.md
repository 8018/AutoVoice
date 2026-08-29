# Qwen Omni S2S 架构与实施计划

更新时间：2026-08-28

## 1. 目标和约束

- 在线语音后端在构建时二选一：`classic`（ASR → DeepSeek）或 `omni` 混合模式。
  混合模式默认仍是 ASR → DeepSeek 业务链路；只有用户明确说“陪我聊会天”后，
  后续语音才进入 `qwen3.5-omni-plus-realtime` S2S 闲聊域。
- 同一份音频始终并发进入候选链路；仲裁只拦截输出，不控制音频是否进入模型。
- 保留两级独立仲裁，不引入三路总仲裁器。
- TTS/播放架构不拆除：文本继续请求 TTS 合成，S2S 音频交给同一个 TTS 播放模块。
- Skill Manager、MCP Registry 和工具执行器复用同一套基础设施；Skill 与 system prompt
  按 `llm`（业务）/`chat`（闲聊）隔离。

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
3. 云端空调命中时拦截在线候选输出但不取消调用；否则放行在线候选。
4. 云端结果到达 Android 后仍需经过端侧仲裁；车窗命中时云端结果被拦截。
5. S2S 音频只有连续通过云端、端侧两道仲裁门后才能进入播放器。

## 3. 构建变体

```bash
./gradlew :app:bootJar -PvoiceBackend=classic
./gradlew :app:bootJar -PvoiceBackend=omni
```

- 公共代码只依赖 `OnlineSpeechProvider`。
- `src/classic/java` 装配 ASR、DeepSeek 和 `speech-classic`。
- `src/omni/java` 同时装配业务 `llm`、`asr-gateway` 与 `speech-qwen-omni`，ASR 负责
  识别框和域路由：默认 DeepSeek，显式进入闲聊后 Qwen S2S；
  ASR 不参与回答、工具调用或语义仲裁。
- CI 检查 Omni JAR 包含 `speech-qwen-omni.jar`、`asr-gateway.jar` 和 `llm.jar`，但不包含
  `speech-classic.jar`。
- 部署工作流通过 `voice_backend` 输入或仓库变量 `VOICE_BACKEND` 选择构建变体。

密钥不进入构建产物。Omni 读取 `DASHSCOPE_API_KEY`，Realtime URL 还需要
`DASHSCOPE_WORKSPACE_ID`（可选的百炼业务空间 ID；留空时使用北京地域通用实时地址）。

每轮音频先经过 ASR，其结果通过独立 `asr_partial(text, isFinal)` 通道即时更新识别框，并作为
域路由依据。默认调用 DeepSeek 业务链路；用户说“陪我聊会天”（兼容“进入闲聊/开始闲聊”）后，
该会话进入闲聊域。之后端侧不再运行本地 ASR/NLU，也不再发普通 `audio_start/audio_end`；原始
16k PCM 连续送入 Realtime 长连接，播放期间也不停。Qwen 的 text modality 是“回答字幕”，不是
用户原话；未启用 input audio transcription，所以闲聊域不更新识别框。闲聊提示词要求跟随用户
当前语音语言回答，除非用户明确要求翻译。

端侧本地链显式拆为 `AsrStage` 与 `NluStage`：通用 ASR 的 partial/final 先上屏，再把最终
ASR 结果交给 NLU；当前讯飞 2C 命令词是“文本+语义同源”，其文本归入 `NluResult`，不能冒充
提前到达的 ASR。只有该 NLU 候选胜出时，才用其自带文本刷新识别框。

## 4. Omni Realtime 请求与工具隔离

闲聊使用 `SDK/QwenOmniRealtime.md` 定义的原生 WebSocket API，模型固定为
`qwen3.5-omni-plus-realtime`：输入 16kHz/mono/PCM s16le，输出 24kHz/mono/PCM s16le，
`turn_detection=semantic_vad`，客户端持续发送 `input_audio_buffer.append`。关闭
`enable_input_audio_transcription`，避免闲聊域再产生 ASR 文本。

模型输出通过 `response.audio.delta` 流式交给原 TTS/AudioTrack 播放入口。收到
`input_audio_buffer.speech_started` 时只关闭当前播放闸门，不发送 `response.cancel`，麦克风与上游
长连接继续工作。模型以 `exit_chat` Function Call 退出锁域。

工具按域隔离：DeepSeek 业务域包含车控/导航与 scope=`llm` 的 MCP Skill；Realtime 闲聊当前只
注入 `exit_chat`，不暴露车控和导航。业务提示词来自 `/api/config/system-prompt`，闲聊提示词来自
`/api/config/chat-system-prompt`，两者分别热更新、互不覆盖；闲聊提示词留空时回退 Realtime 默认。

### 工具循环与导航延迟

- Qwen 工具循环当前硬上限是 **12 次模型调用**，这是兼容 selector 复杂链路的异常兜底，不是正常
  导航的目标轮数；耗尽后会保留已得到的终局 Intent/文本并优雅降级。
- 单/多地点正常目标：一次 `resolve_navigation` → `navigate` → 确认语音，共约 3 次模型调用。
- `resolve_navigation` 内部按口述顺序聚合各地点候选；模型同轮产生的其他独立只读调用可并行，
  写操作形成顺序屏障。同名同参请求在当前语音轮内复用首次结果，不再访问高德。
- Skill 平台的非空 `toolsJson` 是明确勾选清单，未列出的 MCP 工具必须禁用；否则高德全部
  15 个工具会错误触发 selector，凭空增加 `mcp_tools_get` / `mcp_tools_execute` 轮次。
- 推荐启用目的地解析所需的 `maps_text_search`、`maps_around_search`、`maps_geo`；网关会把
  三者聚合并对模型只暴露 `resolve_navigation`，一次调用可解析多个地点并返回导航坐标；导航
  不让模型调用路径规划、schema 拉起、距离或天气工具。生产提示词模板见
  [`prompts/qwen-omni-navigation.txt`](prompts/qwen-omni-navigation.txt)。这些规则属于 DeepSeek
  业务域，不进入 S2S 闲聊提示词。

当前 `Reply` 只能携带一个终局 `Intent`。因此“导航去山姆，同时打开车窗”这类跨域复合指令
不能靠提示词可靠完成两个动作：若模型同轮输出两个终局工具，现实现只保留最后一个。完整支持
需要把下行契约升级为有序 `intents[]`（或批处理 action），端侧逐项执行并分别回报结果。

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
| P6.2 | Omni 混合域路由、业务/闲聊 prompt 与 Skill 隔离 | 已完成（待真实环境验收） |
| P6.3 | qwen3.5-omni-plus-realtime 长连接、常开麦、播放期连续上送与语义打断 | 已完成（待真机验收） |
| P7 | 真实 DashScope、真机、弱网、取消和长音频验收 | 待外部环境 |

## 7. 验证重点

- 云端空调离线命中时，Omni future 和底层 OkHttp Call 被取消。
- 云端离线未命中时，流的 start/chunk/end 顺序稳定，并保留 speakText、asrText 和可选 Intent。
- 端侧车窗命中时不播放云端音频、不执行云端 Intent。
- ASR partial 在两级仲裁尚未收敛时仍能更新识别框；2C 文本仅在其 NLU 胜出后覆盖。
- 回复字幕在端侧云端候选胜出后、音频播放结束前持续更新；本地车窗胜出时不泄漏云端字幕。
- SSE 可处理任意 chunk 边界、多个 audio delta 和 tool arguments delta。
- Classic JAR 不含 Qwen；Omni JAR 同时包含 ASR、LLM 和 Qwen 混合链路，但不含 speech-classic。
- 真实 DashScope 与 Android 真机验收完成前，产品状态标注为“代码链路已流式、外部环境待验收”。
