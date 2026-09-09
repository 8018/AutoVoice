# 对话控制器提取（第四阶段）

## 目标

`ConversationController` 是普通语音对话生命周期的唯一入口。它统一拥有 `captureId`、
capture 准入、云端 pending 归属和当前 `turnId`，避免 `VoiceEngine`、录音层和 UI 分别保存
一份轮次状态后发生漂移。

## 职责边界

- `ConversationController` 接收唤醒、录音候选、ASR/NLU 准入、pending、最终语义和播放
  生命周期事件，并发布一个 `DialogueSnapshot`。
- `DialogueStateMachine` 仍是纯状态转换组件，只知道当前 interaction/turn，不知道 VAD、
  文本、仲裁规则或播放资源。
- `TurnAdmissionGate` 仍只判断某个 capture 是否被 ASR 或有效最终语义确认。
- `VoiceEngine` 不再直接组合状态机和准入门；它消费控制器结果，连接遥测、业务执行、
  播放协调器和网关。
- `RecordingCoordinator` 只观察控制器快照来协调共享麦克风，不修改对话状态。
- 语义仲裁器仍是无“当前轮”概念的流水线，只维护每轮是否已输出过语义。

## 关键规则

1. 录音开始先创建 `captureId`，VAD 只打开候选，不改变当前 `turnId`。
2. ASR `turnEstablished` 或有效最终语义把 capture 晋升为 turn；晋升时才允许停止旧播报。
3. 仲裁结果到达后，控制器判断其是否仍是当前 turn；迟到的旧轮结果不会执行或播报。
4. pending 只有在归属于当前候选或当前 turn 时才对 UI 可见；唤醒、重置或延时窗口结束
   会一并清理 pending 与候选，空 id 永不显示 pending。
5. 播放开始/结束必须携带通过 `PlaybackCoordinator` 校验的 turnId，过期回调不会改变状态。

## 验证

单元测试覆盖 VAD 误报不抢轮、pending 在准入后进入语义处理中、迟到语义/播放回调被
忽略、重置和重新唤醒原子清理状态。完整回归执行 Android/JVM 单测、Debug APK 构建与
lint；状态机、播放、录音和网关协议行为保持不变。
