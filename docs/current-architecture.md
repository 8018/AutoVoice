# 当前架构入口

本页对应 `codex/chat-mode-lifecycle-2026-09-27` 开发分支，基于已合入 `dev` 的 `cb307a3`；不是 main 或生产部署声明。`dev` 基线 CI 和自动部署已通过，本分支改动仍需单独验收。

## 边界

`audio-frontend` 处理信号与 VAD；本地/云端 ASR 负责文本与话语成立，NLU 负责语义。端侧仲裁器是常驻候选流水线，只决定每个 turn 是否已输出及候选优先级；`ConversationController` 才判断是否为当前轮。`VoiceEngine` 负责这些边界的编排，采用后的语义交给 `BusinessHandler`。

`AppDialogueManager` 负责导航任务、候选选择、延时聆听 revision、后台及连接变化的任务收口，并拥有进入/退出闲聊域的业务决定；`MainViewModel` 只转发事件并投影状态。`RecordingCoordinator` 的锁域标记只决定麦克风路由，`GatewayRealtimeChatChannel` 的 desired/ready 与 generation 只表示上游传输事实。普通会话状态仍由 `ConversationController` 管理，导航多轮状态仍由 `NavigationSession` 管理，三者不是同一个状态机。

`GatewayClient` 仅负责连接与原始消息；`GatewayProtocolSender` 负责编码上行；`GatewayBridge` 按消息类型将下行交给 ASR/NLU、音频、TTS、闲聊监听者。`GatewayTtsTransport` 单独管理 TTS 请求超时；`GatewayNavigationContextChannel` 管导航上下文发布和缺失反馈；`GatewayRealtimeChatChannel` 管闲聊连接、重连和输出代次。它们复用同一 WebSocket。闲聊输出使用 `RealtimePlaybackToken(generation,responseId)`，不伪造普通 turnId。新版端云协议还回显 `chatId`，客户端拒绝带旧 `chatId` 的迟到回复；旧服务端不回显时仍走兼容路径，不保证同等隔离。

`TtsOutput` 内部持有合成、缓存、播放身份。业务输出与播放完成分开：未请求播放的业务结果通过 `onOutputSkipped` 从 RESPONDING 进入延时聆听，播放结果则由 TTS 生命周期驱动。

## 当前约束

- 断线结束当前操作；不重放音频、不补执行，不使用持久化动作账本保证恢复。
- 导航上下文随任务身份撤销；重连不恢复待选任务。
- `dev` 基线 `cb307a3` 已通过 CI 并自动部署；本分支尚未部署。真机、真实 SDK、付费模型与故障/容量场景仍需单独验收，不可把单测通过等同于生产发布。
- `GatewayBridge` 和服务端 `VoiceGatewayHandler` 仍是通信聚合点；进一步按业务边界拆分需保持协议兼容并分批验收。
