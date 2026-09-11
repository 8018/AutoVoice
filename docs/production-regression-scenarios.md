# 生产回归场景清单

制定日期:2026-09-11。依据:[生产架构评估](production-architecture-review-2026-09-11.md)第二节
"必须保留的架构设计"。每一条架构约束至少有一个明确的回归用例归属——已存在标注测试类,
未落地标注所属工作包(D0X)。后续工作包完成时在本表补充实际测试类名。

## 1. 音频与候选并发,不允许"未命中才上传/才给模型"

| 场景 | 断言 | 归属 |
| --- | --- | --- |
| 在线候选与离线候选同时启动,不互相等待 | audio_start 即启动流式 ASR;audio_end 同时启动离线与在线候选 | `VoiceGatewayHandlerTest`(audio_start/audio_end 时序)、`SegmentPipelineTest` |
| 端侧与云端候选并行,端侧不阻塞云端 | 端侧竞速期间云端候选仍完成 | `VoiceEngineTest`(on-device 竞速) |

## 2. 两级仲裁硬优先级,宽限与兜底是显式策略

| 场景 | 断言 | 归属 |
| --- | --- | --- |
| 端侧车窗命令优先于云端结果 | on-device 胜出后云端结果被拦截 | `OnDeviceRaceArbiter` 相关测试(VoiceEngineTest 端侧胜出用例) |
| 云端离线命令在宽限期内优先于模型 | offline_won / llm_reply 路由与 offlineGraceMs 窗口 | `RaceArbiterTest` |
| safety 超时兜底不变成先到先得 | LLM 未到时按 safetyTimeout 兜底,不取首个到 | `RaceArbiterTest` |

## 3. 仲裁器只按该轮记录一次语义输出,无"当前轮"概念

| 场景 | 断言 | 归属 |
| --- | --- | --- |
| 同轮第二份语义被拦截 | 每轮语义只声明一次 | `VoiceGatewayHandlerTest`(TurnOutputPermit 相关)、`RaceArbiterTest` |
| 旧轮晚到仍可完成该轮首次仲裁 | 新轮注册不覆盖旧轮仲裁状态 | `VoiceGatewayHandlerTest`(reconnect/晚到用例) |

## 4. 当前轮判断归会话/输出协调层;落败不取消候选计算

| 场景 | 断言 | 归属 |
| --- | --- | --- |
| 落败/被替代轮仅拦截输出,候选继续计算 | 拦截不取消 ASR/LLM/工具 Future | `VoiceGatewayHandlerTest`(cancel/superseded 用例) |
| 语音流不因已有语义输出而只播第一块 | 同轮后续音频块由同一输出许可放行 | `VoiceGatewayHandlerTest`、`PlaybackCoordinatorTest` |

## 5. VAD 只做分段,不建业务轮、不直接停播

| 场景 | 断言 | 归属 |
| --- | --- | --- |
| VAD 检测到噪声不建立新轮 | 仅 ASR 话语成立或有效 NLU 才换轮 | `RecordingCoordinatorTest`、`TurnAdmissionGate` 相关测试 |
| VAD start 不停止 TTS 播报 | 播报期 VAD 门与主分段门分离 | `OpenMicBargeInGateTest` |

## 6. ASR 独立上屏,语义获准后带识别文本再刷新

| 场景 | 断言 | 归属 |
| --- | --- | --- |
| asr_partial/文本旁路不等待仲裁 | 识别文本独立刷新识别框 | `GatewayBridgeTest`(asr 回调)、协议 fixture 对拍 |
| 获准输出的 NLU 带识别文本时刷新识别框 | asrText 随获准语义下发 | `ResponseDispatcherTest`(onRecognized) |

## 7. 业务域 ASR/LLM 与显式闲聊 Realtime 隔离

| 场景 | 断言 | 归属 |
| --- | --- | --- |
| 闲聊锁域期间业务域工具不注入 | llm/chat scope 工具隔离 | `McpSkillRegistry` 相关测试、`HybridBusinessChatSpeechProvider` 路由测试 |
| 闲聊退出后恢复正常链路 | 锁域释放、system prompt 复位 | `RecordingCoordinatorTest`(realtime chat 恢复唤醒) |

## 8. 导航候选归属逻辑会话,TTL 与 selectionId 校验

| 场景 | 断言 | 归属 |
| --- | --- | --- |
| 候选过期(TTL)后不可选 | 120s TTL + selectionId 校验 | `navigation-domain` 测试、`NavigationSessionTest` |
| 重连恢复保留有效候选 | 恢复凭据 + 候选版本校验 | D02/D15(待落地) |
| 说"第一个"/地址名只操作当前有效候选 | candidateId/selectionId 绑定 | D05(待落地) |

## 附:事件字段约定(D01b)

- 统一字段:`subject` / `session` / `turn` / `request` / `configVersion` /
  `stage` / `result` / `reason`(`TelemetryFields` 常量,`TelemetryEvent` 结构化组件)。
- 不记录密钥:敏感键判定见 `TelemetryFields.isSensitiveKey`;音频与用户原文不作为
  默认诊断字段;请求 ID 只作关联字段,不成为指标标签。
