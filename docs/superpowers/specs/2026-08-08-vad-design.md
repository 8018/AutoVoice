# Silero VAD 全自动免按录音 设计

**Goal:** 端侧恢复开源 VAD 管线，实现"全自动免按"录音交互——无按钮，常驻监听，检测到语音自动开始录音、检测到语音结束自动送识别。

**背景：** Task 16 集成过 Silero VAD v5 + VoiceActivityGate（双网格管线），Task 34 因讯飞 ESR 引擎内部自带 VAD 而退役（段边界改为按住/抬手）；唤醒方案因体验版授权冲突放弃后回到按钮模式。用户确认：重新集成开源 VAD，交互升级为全自动免按。选型结论：**复用仓库已有的 Silero VAD v5**（封装类/门控/模型/单测俱在，Task 33 已用 python onnxruntime 交叉验证推理正确性；业界标准精度；2.3MB 模型 + ~0.1ms/帧 CPU 适合常驻监听）。备选 WebRTC VAD（噪声下不可靠）与 sherpa-onnx（集成过重）均不采纳。

## 1. 交互语义

```
启动 → 常驻监听（麦克风常开，静音待机）
  SpeechStart（连续 64ms 语音）→ 自动进入录音：收集降噪 PCM 块
  SpeechEnd（静音 960ms）     → 自动结束：拼接段送双链路竞速
  播报/执行 → 回待机继续监听
```

- 无按钮；`RECORD_AUDIO` 权限启动即申请（恢复 Task 35 的 LaunchedEffect 形态）。
- 语音段边界 = SpeechStart..SpeechEnd；ESR 引擎内部 VAD 继续负责段内子句分割（两层 VAD 职责分离，互不冲突）。

## 2. 管线（AudioRecorder 恢复双网格，Task 16/18 原设计）

```
AudioRecord (16k mono PCM16) ──1024B/块──┬─▶ SileroVad.feed(512 samples) → 概率 → VoiceActivityGate → VadEvent 流
                                         └─▶ RNNoise 降噪（480 帧网格，尾 32 samples 丢弃）→ 960B/块 → pcmBlocks 流
```

- 两网格独立并行、互不干扰；VAD 用**原始块**（silero 要求 512 samples 整帧），降噪用独立网格。
- 门控参数沿用既有默认：`threshold=0.5`、`minSpeechMs=64`、`minSilenceMs=960`、16k。
- `AudioRecorder` 重新暴露 `vadEvents: SharedFlow<VadEvent>`；`pcmBlocks` 不变。

## 3. 状态机与兜底（MainViewModel）

- **SpeechStart** → `segmentBlocks.clear()` + `speechActive=true` + `engine.onListeningStart()`（恢复云端路由）+ UI「聆听」；同时启动**录音段超时 Job**。
- **SpeechEnd** → `speechActive=false` → 拼接段：
  - 段 < `MIN_SEGMENT_MS`（300ms）→ 丢弃不送识别（瞬时噪声/关门声误触发过滤），仅日志；
  - 否则 `engine.onVadSegment(segment)` → `engine.onListeningStop()` → 回待机。
- **录音段超时**：SpeechStart 后 `MAX_SEGMENT_MS`（10s）无 SpeechEnd → 强制按 SpeechEnd 结束（防环境噪声持续触发无限录音）。段超时结束时若长度达标仍送识别。
- **失败静默**：误触发段竞速失败（`RaceWinner.Failed`）**不播报**兜底话术——避免噪声反复播「网络开小差了」；决策日志照记。`VoiceEngine.onTurnResult` 的 Failed 分支改为静默：删除 `FALLBACK_PHRASE` 常量与播报（全自动模式下没有用户主动段，无兜底话术场景）。

## 4. UI 变化

- 移除 `RecordButton` 与 `onStartRecording/onStopRecording` 回调；`MainActivity` 恢复启动即申请权限。
- `UiState.recording` 字段移除；待机/录音态由 `sessionState` 驱动（IDLE=待机、LISTENING=录音、UNDERSTANDING=识别中…）。
- 底部文案：待机「聆听中… 请说指令（如"打开空调"）」；录音/识别中「识别中…」。权限提示文案沿用。
- 其余（状态头/识别卡片/决策日志/设置区）不动。

## 5. 错误与降级

- `SileroVad` 模型加载失败（assets 缺失/推理异常）→ 日志告警 + UI 提示「语音检测不可用」，不崩应用；`vadEvents` 无事件（等同系统不可用，demo 接受）。
- 常驻麦克风为免按模式的必然代价，demo 接受。

## 6. 测试与验收

- 既有单测保持绿：`VoiceActivityGateTest`（门控状态机）、`SileroVadModelTest`（真实推理）。
- 新增 JVM 测试：段拼接/最小段过滤/超时强制结束的逻辑若抽成纯 Kotlin 组件（如 `AutoSegmenter`），则单测覆盖；否则真机验证。
- 真机验收：
  1. 免按说「打开空调」→ 自动 SpeechStart/SpeechEnd → 送识别 → `climate/power_on` → 播报 → 回待机；
  2. 静音 3s 不误触发（无 SpeechStart）；
  3. 噪声/音乐 >10s → 录音段超时强制结束，不无限录音；
  4. demo-full 与 demo-offline 模式切换后 VAD 行为一致；
  5. 无结果时无兜底话术播报（失败静默）。
