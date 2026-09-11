# Android Demo 配置能力清单

本文记录 `demo-full.json` / `demo-offline.json` 中每个字段的默认值、装配点和最终消费者。
JSON Schema 与运行时校验必须同时更新；不能只让配置“解析成功”却不改变实际链路。

| 字段 | 默认值 | 装配点 | 最终消费者与行为 |
| --- | --- | --- | --- |
| `mode` | 必填 | `MainViewModel` 选择对应资产，`DemoConfig.validateForRuntime` 校验 | 只允许 `full` / `offline`；云端是否启用仍以 `cloud.enabled` 为准。 |
| `vad.threshold` | `0.5` | `AudioRecorder` → `VadConfig.createSegmentationGate` | 主录音的 Silero VAD 分段门限，范围 `[0,1]`。 |
| `vad.minSpeechMs` | `64` | 同上 | 主录音连续语音成立时间，范围 `1..60000ms`，按 32ms 帧向上取整。 |
| `vad.minSilenceMs` | `960` | 同上 | 主录音语音结束静音时间，范围 `1..60000ms`，按 32ms 帧向上取整。 |
| `ecnr` | `rnnoise` | `MainViewModel` → `AudioRecorder` | `rnnoise` 对本地整段音频降噪；`none` 旁路降噪。其他值启动期拒绝。 |
| `local.asr` | 必填 | `VoiceEngineFactory.buildLocalChain` | `iflytek.offline` 使用真实 2C 命令词 SDK；不可用时该候选未命中。`iflytek.fake-cmd` 仅在显式配置时产生 Demo 命令。 |
| `local.nlu` | 必填 | `VoiceEngineFactory.buildLocalChain` | 当前只支持 `rule.nlu`，其他名称启动期拒绝。 |
| `cloud.enabled` | 必填 | `VoiceSession` | 是否启动云端候选；关闭时只走本地候选。 |
| `cloud.gatewayUrl` | 必填 | `GatewayCloudRunner` | WebSocket 网关地址；`cloud.enabled=true` 时不得为空。 |
| `cloud.waitMs` | 必填 | `OnDeviceRaceArbiter` | 端侧等待云端结果的第一阶段时间，必须大于 0。 |
| `cloud.deviceId` / `authToken` | 无 | `GatewayCloudRunner` | 网关 hello 鉴权；未配置时不发送对应字段。 |
| `cloud.telemetry.enabled` | `true`（仅当 telemetry 段存在） | `VoiceEngineFactory` | 控制端侧链路遥测；整个 `telemetry` 段缺失时关闭。 |
| `cloud.telemetry.url` | 由 `gatewayUrl` 推导 | `TelemetryClient` | 遥测 HTTP 基址；空字符串按未配置处理。 |
| `mock.executor` | `false` | `DemoConfig.validateForRuntime` | 保留字段。当前车辆执行器本身就是 App 内 Mock，`true` 没有额外实现并会明确拒绝。 |
| `testAudio` | 无 | `MainViewModel` → `TestAudioSource` | 指定 16kHz mono PCM16 asset 时替代麦克风，仅用于确定性 Demo/设备联调；按当前模式配置读取。 |

## VAD 参数边界

上述 `vad.*` 只控制“普通一轮录音”的云端分段，不直接建立对话轮次，也不停止 TTS。
播报期开放式打断和播报后的延时聆听使用独立门限（当前为 `0.65 / 160ms / 320ms`），
因为它们需要更严格地防止扬声器回声触发；300ms 最小段过滤也仍是独立的后处理规则。

模式切换会先结束当前采集，再把新配置重新应用到 `AudioRecorder`。因此修改 VAD 静音时间会
改变 `SpeechEnd` 和分段时机，但不会绕过 ASR/有效 NLU 才能建立新业务轮的准入规则。

## 失败与兼容策略

- 旧配置省略 `ecnr` 时保持历史实际行为，按 `rnnoise` 处理。
- 未知 mode/provider、非法 VAD 数值、非正 `cloud.waitMs` 和启用云端却没有网关地址都会给出
  带字段名的错误并拒绝装配，不会因拼错 provider 而切到另一能力。只有资产文件缺失时才使用
  代码内的 Demo 默认配置。
- `iflytek.offline` 缺少 SDK、凭据、授权或资源时只让真实本地候选未命中，不会暗中伪造命令。
  需要无 SDK 演示时必须明确配置 `iflytek.fake-cmd`。
