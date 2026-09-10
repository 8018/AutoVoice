# 对话事件顺序与采集资源归属

## 问题

对话准入可能停止播放，播放回调又会推进对话状态。两边原先都在各自同步锁内调用外部
回调，并发发生 ASR 准入和播放结束时存在相反的锁获取顺序。

另一个独立问题是 `VoiceSession` 曾用已准入的业务轮 ID 保护录音编排资源。新 capture
已经开始但 ASR/NLU 尚未确认时，旧轮收尾仍可能被判断为当前任务，进而清理新 capture
的云端片段或把会话状态改回 IDLE。

## 修改

- `ConversationController` 在一个内部锁中完成状态变更，并把外部回调写入 FIFO 队列；
  队列在锁外排空。回调重入只会追加后续事件，不再形成对话锁到播放锁的调用链。
- `PlaybackCoordinator` 先在锁内核验固定播放身份，再按 FIFO 在锁外通知状态与遥测；
  驱动操作单独串行，避免新播放与旧播放的 stop 交错。
- `VoiceSession.onListeningStart(captureId)` 在录音开始时立即接管编排资源。这个 ID 只保护
  音频片段、SessionState 和结果任务；`ConversationController` 仍只在 ASR 或有效语义
  准入后更新业务当前 turn。

仲裁器仍不判断当前业务轮，也不取消本地或云端输家。VAD 仍只打开候选 capture。

## 验证

并发回归覆盖：准入回调阻塞时播放结束仍能更新状态且回调顺序不乱；播放事件消费者阻塞
时新播放身份仍可建立；旧轮在新 capture 尚未语义准入时完成，不得覆盖新录音的 LISTENING
状态或资源所有权。完整回归执行 JVM/Android 单测、Debug APK 构建与 lint。
