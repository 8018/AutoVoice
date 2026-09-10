# 端云轮次准入与输出分层

更新日期：2026-09-10

## 背景

客户端 VAD 检测到声音时，新的音频已经会并发送给端侧和云端能力。但 VAD
只能证明“可能有声音”，不能证明“新业务轮已经成立”。如果服务端在
`audio_start` 时就作废旧轮，TTS 回声、环境噪声或过短语音都会拦截本来应该
到达的旧轮回复。

本设计把“采集候选”、“业务轮准入”、“语义仲裁”和“输出执行”分成四个
独立关注点。

## 责任边界

1. `ConversationController` 是 Android 端当前 `turnId` 的唯一拥有者。
   VAD 只创建/打开 capture，ASR 话语成立或有效最终语义才会把 capture 晋升为 turn。
2. 端侧和云端仲裁器不知道“当前轮”。它们只根据固定优先级选择每个
   `turnId` 的首个有效语义，该轮已经输出后才拦截同轮的其他结果。
3. `ConnectionTurnCoordinator` 只管服务端每条 WebSocket 的任务槽：一个正在处理的
   轮和一个等待候选。它不使用 VAD 判断新轮。
4. `ResponseDispatcher` 只负责把已接受的语义分发到 UI、导航/车控和语音输出。
   `SpeechOutputService` 只负责 TTS、缓存和播放，不理解业务意图。

## 准入协议

```text
VAD start
   └─ audio_start + PCM     创建候选，旧轮仍可输出

ASR turn-established 或有效最终语义
   ├─ Android confirmTurn  晋升本地当前 turnId
   └─ turn_commit          告知服务端候选已成为业务轮

Server turn_commit
   └─ 作废旧轮的输出闸门，不取消旧轮端侧/云端计算
```

服务端在自身流式 ASR 确认话语成立时，会先执行同样的准入，再向客户端
发送 `asr_turn_started`。因此，无论证据先在端侧还是云端产生，都使用同一条
规则。`turn_commit` 重复发送是幂等的。

## 身份安全

- pending 由具体 `turnId` 拥有；过期轮不能覆盖新轮 pending，也不能清除新轮的
  “正在处理”提示。
- 服务端决策日志按 `utteranceId` 收集和下发，新的 `audio_start` 不会清除
  旧轮日志，也不会把旧轮日志带到新轮。
- TTS 请求和缓存事件携带发起播报时的固定 `turnId`，不从可变的“当前轮”
  反查归属。
- 客户端最终仍由 `ConversationController.isCurrentTurn` 判断迟到结果是使用还是丢弃。

## 生产与 Demo 隔离

`local.asr=iflytek.offline` 未配置、授权失败或 SDK 异常时，本地候选按未命中
收口，不再伪造“打开空调”。只有显式配置 `local.asr=iflytek.fake-cmd` 才使用确定性
Demo provider。

## 输出分层与 TTS 缓存

- `ResponseDispatcher` 处理 `TextReply` / `ActionReply` / `AudioReply` /
  `StreamingAudioReply` 的业务路由。
- `SpeechOutputService` 统一进行轮次校验、TTS 请求、缓存命中和播放准备。
- TTS 磁盘缓存保存 `sha256(text).audio` 和 `sha256(text).mime`，避免把 MP3/PCM
  误当作 WAV 播放。旧版 `.wav` 文件仍可读，新写入使用新格式。

## 边界与后续

本阶段已把连接任务槽从 `VoiceGatewayHandler` 提取出来，但 Handler 仍负责协议分派、
Realtime 闲聊、TTS 请求和下行编码。后续应按保持行为的小步骤继续提取
`GatewayOutboundChannel` 和 `RealtimeChatHandler`，不在同一次修改中重写整个网关。

## 验证要点

1. VAD 候选到达时，旧轮仍能回复。
2. ASR 或有效语义准入后，旧轮迟到回复被拦截。
3. 每连接最多一个处理轮和一个等待候选，超出时返回 `BUSY`。
4. 过期 pending 不能影响当前轮。
5. 生产离线 SDK 不可用时不产生模拟车控意图。
6. TTS 缓存冷启动后仍使用 provider 的真实 MIME。
