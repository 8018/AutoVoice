# 对话状态、语义仲裁与延时聆听设计

## 目标

把本地对话状态、ASR/NLU、端云仲裁和输出执行拆开，避免以下问题：

- VAD 误报一出现就替换当前轮，导致仍有效的上一轮结果或播报被提前丢弃。
- 仲裁器通过全局“最新轮”判断候选，混淆了仲裁规则与本地交互生命周期。
- 语义处理完成后旧会话立即进入 IDLE，但 TTS 仍在异步播放，造成状态、字幕和录音重启时序错位。
- 播报结束后只能重新唤醒，不能在有限窗口内直接追问。

## ID 与职责

- `interactionId`：一次唤醒到延时聆听结束的连续交互。
- `captureId`：一次 VAD/录音候选。它可以被 ASR 判定为有效语音，也可以作为噪声丢弃。
- `turnId`：被语音证据确认后的业务轮。当前实现直接把已确认的 `captureId` 晋升为 `turnId`。

`captureId` 可以立即用于网关对账和遥测，但不能在确认前替换状态机当前 `turnId`。

## 两层拦截

两层判断必须严格分开：

1. 语义仲裁流水线只维护“该 `turnId` 是否已经输出过语义”。它不知道哪个是当前轮。未输出时继续按硬规则仲裁；已输出时以 `turn_already_output` 拦截该轮后续语义。
2. 本地对话状态机维护当前 `turnId`。仲裁输出到达后，状态机判断是否仍为当前轮；是则采用并执行，不是则丢弃。

因此数据方向是：

`并发候选 → 端/云硬规则仲裁 → 按轮单次语义输出 → 状态机当前轮校验 → 执行/TTS`

ASR 提供两个独立输出：字幕按自己的 `turnId`/`captureId` 对账后立即更新识别框；
`turnEstablished` 由 ASR/AEC 在确认有效新话语后发出。两者都不等待语义仲裁，状态机也
不得从字幕文本反推 `turnEstablished`。

## 既有硬规则

- 同一份音频并发进入本地和云端候选，不以“本地未命中”作为上传条件。
- 端侧：本地车窗命令优先；否则在窗口内等待云端，超时后使用有效本地结果。
- 云端：空调离线语义优先；否则等待在线模型。
- 仲裁收敛不取消输家，迟到候选仍可自然完成；是否还能输出由该轮的语义输出账本判断。

## 本地状态机

状态如下：

- `DORMANT`：未唤醒。
- `AWAKE`：已唤醒，尚未检测到候选语音。
- `SPEECH_CANDIDATE`：VAD 已触发，但尚未确认是有效新轮。
- `THINKING`：ASR 的 `turnEstablished` 或有效最终语义已把 capture 晋升为 turn。
- `SEMANTIC_PROCESSING`：收到 pending/模型处理中信号。
- `RESPONDING`：当前轮最终语义已被采用，正在准备执行或音频。
- `SPEAKING`：播放器真实开始播放。
- `FOLLOW_UP_LISTENING`：播放器真实结束后的免唤醒追问窗口。

核心触发点：唤醒、VAD start、ASR `turnEstablished`、语义处理中、最终语义、TTS 播放开始/结束、延时聆听定时器结束。

## VAD 误报与轮次准入

VAD start 只创建临时 capture，不替换当前 turn。准入证据为：

1. 本地 ASR 明确发出的 `turnEstablished`；
2. 云端 ASR 明确发出的 `asr_turn_started`；
3. 本地有效最终语义；
4. 没有 ASR 的链路以有效云端最终语义兜底。

识别文本和轮次准入严格分离：partial/final 仅用于识别框和 NLU，文本是否为空、长度、
稳定次数及与 TTS 的相似度都不能由状态机判断。TTS 回声、AEC 和识别稳定性属于 ASR
模块；ASR 未发出 `turnEstablished` 且双路均无有效语义时拒绝 capture。

## 延时聆听

- 普通回复在播放器 `completed` 后进入 10 秒延时聆听，而不是在语义结果到达或旧 Session 进入 IDLE 时启动。
- 延时聆听复用唯一 `AudioRecord` 和独立 Silero VAD；无需 AEC，因为此时扬声器已停止播放。
- 保留约 384ms 预卷音频，VAD 命中后建立临时 capture，避免丢失话首。
- 延时窗口内的 VAD 仍需通过 ASR/有效语义准入，误报后恢复延时聆听。
- 定时器到期后回到 `DORMANT` 并恢复离线唤醒。
- App 退后台、模式切换或进入 S2S 闲聊锁域时取消普通延时聆听。

导航候选等明确期待用户回答的场景后续可把窗口扩展到 30 秒，并设置单次 interaction 的 60 秒绝对上限；普通命令默认保持 10 秒。

## 当前代码落点

- `voice-core/dialog/DialogueStateMachine.kt`：当前轮和本地交互状态。
- `voice-core/dialog/TurnAdmissionGate.kt`：VAD capture 准入。
- `voice-core/arbiter/SemanticEmissionLedger.kt`：仲裁流水线按轮单次语义输出。
- `VoiceSession`：只负责候选并发编排，不再把当前轮判断塞进仲裁结果。
- `GatewayBridge`：按 `segmentId` 分别对账 `asr_partial` 和 `asr_turn_started`，不把识别文本隐式转换为新轮。
- `VoiceEngine`：连接准入、状态机当前轮校验和执行/TTS。
- `MainViewModel` / `AudioRecorder`：真实播放结束计时、共享麦克风和延时聆听 VAD。
