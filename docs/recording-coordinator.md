# 录音生命周期提取（第三阶段）

## 目标

共享麦克风只有一个拥有者：`RecordingCoordinator`。它决定当前原始/降噪 PCM 应送往
离线唤醒、普通轮、延时聆听 VAD 或 Qwen Realtime，但不判断识别文本、业务意图或
仲裁结果，也不直接修改 `DialogueStateMachine`。

## 边界

- `AudioRecorder` 实现 `RecordingCapture`，只管理 AudioRecord、AEC、Silero VAD、
  RNNoise 和音频事件流。
- `WakeWordPort` 只消费共享原始 PCM；讯飞唤醒 SDK 不创建第二路 AudioRecord。
- `RecordingPipeline` 是到当前 `VoiceEngine` 的动态端口。切换 demo 模式后，协调器继续
  使用同一麦克风，但所有事件自动转发到新引擎。
- `MainViewModel` 只把 Activity 生命周期、UI 操作和引擎回调转给协调器，再把
  `RecordingLifecycleSnapshot` 映射为 UI 字段。

对话状态、播放状态和录音状态保持独立：协调器只观察已产生的 `DialogueSnapshot` 与
已验真的 `PlaybackStage`，用来开关延时聆听或开放式打断；它不生成新的对话状态，
不把 VAD 命中当作当前轮。

## 路由规则

1. 待机：原始 PCM 送讯飞唤醒；播报期/延时窗口可同时送独立 VAD。
2. 普通轮：降噪 PCM 累积为本地 ASR 整段；Silero SpeechStart 后同一批 PCM 流式送云端，
   SpeechEnd 只让唤醒进入的轮自动收口。
3. S2S 闲聊：原始 PCM 持续送 Qwen Realtime，不进入本地 VAD、ASR/NLU 或普通云端链。
4. 退后台：停止共享麦克风并结束 realtime；前台恢复时按当前锁域重新建立唯一采集流。

所有超时任务由协调器持有：唤醒轮 10 秒安全收口、普通延时聆听 10 秒、导航选择
30 秒，以及单 interaction 60 秒上限。模式切换、后台和销毁统一取消对应任务。

## 失败与恢复

- 普通采集启动失败时回滚引擎 capture、重置普通对话并向 UI 报告录音权限。
- 唤醒 SDK 失败只解除唤醒观察，不关闭共享麦克风或 AEC 打断能力。
- 离开 S2S 时显式结束 realtime 并重置普通对话，随后恢复唤醒。
- 引擎重建后立即同步新的空 `DialogueSnapshot`，旧引擎状态不会阻止唤醒恢复。

## 验证

JVM 测试使用假的 `RecordingCapture`、`WakeWordPort` 和 `RecordingPipeline`，覆盖唤醒轮
自动收口及双路音频顺序、S2S 原始流独占、延时聆听定时器和采集失败回滚。完整回归仍
执行 Android 单测、Debug APK 构建与 lint；真机麦克风、AEC 和讯飞资源需在合并后验收。
