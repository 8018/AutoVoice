# 当前架构入口

本页对应 `codex/architecture-hardening-2026-09-27` 开发分支；不是 main 或线上部署声明。验收状态以该分支的 CI、真机与 dev 环境结果为准。

## 边界

`audio-frontend` 处理信号与 VAD；本地/云端 ASR 负责文本与话语成立，NLU 负责语义。端侧仲裁器是常驻候选流水线，只决定每个 turn 是否已输出及候选优先级；`ConversationController` 才判断是否为当前轮。`VoiceEngine` 负责这些边界的编排，采用后的语义交给 `BusinessHandler`。

`AppDialogueManager` 负责导航任务、候选选择、延时聆听 revision、后台及连接变化的任务收口；`MainViewModel` 只转发 UI/录音事件并投影状态。普通会话状态仍由 `ConversationController` 管理，导航多轮状态仍由 `NavigationSession` 管理，二者不是同一个状态机。

`GatewayClient` 仅负责连接与原始消息；`GatewayProtocolSender` 负责编码上行；`GatewayBridge` 按消息类型将下行交给 ASR/NLU、音频、TTS、闲聊监听者。`GatewayTtsTransport` 单独管理 TTS 请求超时，回复槽仍由 Bridge 对账，复用同一通道。闲聊输出使用 `RealtimePlaybackToken(generation,responseId)`，不伪造普通 turnId；退出或重连会使已分发的旧 generation 输出失效。协议目前未给闲聊回复携带 chat generation；新会话开始后才抵达的旧回复仍需服务端/协议端到端身份才能可靠识别。

`TtsOutput` 内部持有合成、缓存、播放身份。业务输出与播放完成分开：未请求播放的业务结果通过 `onOutputSkipped` 从 RESPONDING 进入延时聆听，播放结果则由 TTS 生命周期驱动。

## 当前约束

- 断线结束当前操作；不重放音频、不补执行，不使用持久化动作账本保证恢复。
- 导航上下文随任务身份撤销；重连不恢复待选任务。
- 真机、真实 SDK、dev 部署和付费模型尚未在本分支验收，不可把单测通过等同于生产发布。
- `GatewayBridge` 和服务端 `VoiceGatewayHandler` 仍是通信聚合点；进一步按业务边界拆分需保持协议兼容并分批验收。
