# 客户端通信、TTS 与业务边界

## 目标依赖方向

```text
Recording / UI
      |
      v
 VoiceEngine  ----> TtsOutput (:tts)
      |                 |-- cache（内部）
      |                 |-- synthesizer（内部选择）
      |                 `-- playback coordinator（内部） -> Android playback driver
      v
BusinessHandler (:business-core)
      |
      `-- AppBusinessHandler
          |-- NavigationExecutor
          `-- MockVehicleState / 后续真实车控 adapter

GatewayCloudRunner
      |-- GatewayProtocolSender（audio/chat/tts/turn 协议）
      |-- GatewayBridge -> MessageDispatcher（按 type 多播）
      |                    `-- GatewayPayloadParser（无状态解析）
      `-- GatewayClient（WebSocket 通道）
          `-- GatewayConnectionPolicy（心跳/超时/重连）

Local semantic pipeline                 Cloud semantic pipeline
  |-- local AsrEngine                     |-- CloudAsrEngine (typed listener)
  `-- local NluEngine                     `-- CloudNluEngine (typed listener)
```

## 边界约束

1. `GatewayClient` 只管理连接、原始文本/二进制帧收发和连接状态。不得新增
   `sendAudioStart`、`sendTtsRequest` 等业务方法。
2. 上行协议命令统一放在 `GatewayProtocolSender`。连接失败结束当前轮，音频不得自动重放。
3. 下行消息必须先经 `MessageDispatcher`。监听器注册时声明消息类型；一个类型允许多个监听器，
   注销一个监听器不得影响其他监听器。
4. `GatewayPayloadParser` 只解析协议，不持有连接、轮次或 UI 状态。
5. `VoiceEngine` 只负责采集准入、状态机、仲裁和把胜出结果交给 `BusinessHandler`；不得依赖导航、
   车辆状态或具体业务 SDK。
6. `TtsOutput` 是播报、已有音频播放、流式播放、停止和播放事件的唯一入口。缓存 key、磁盘格式、
   命中策略、生成实现和播放身份协调均不向调用方暴露；Android 只实现底层播放 driver。
7. ASR 与 NLU 是两个独立引擎。端侧按 `local AsrEngine -> local NluEngine` 组合；云端 ASR 与
   云端 NLU 分别注册自己消费的消息类型，ASR 文本不经过语义仲裁。
8. 仲裁器是进程级常驻的 FIFO 消息流水线，不拥有会话生命周期、不识别“当前轮”。
   它只记录每个 `turnId` 是否已输出语义；当前轮和状态有效性由会话状态机判断。
9. 端侧云端语义与本地车窗语义可立即入队；本地普通语义在云端优先窗口内留在
   入队门外。一旦进入就绪队列，按消息到达顺序处理，首个合格候选胜出。

## 新业务接入

- 新增业务域：在 app 组合根注册新的 `BusinessHandler`，不修改 `VoiceEngine`。
- 新增网关消息：在桥中注册该消息类型的监听器，不修改 WebSocket 连接类。
- 替换 TTS 厂商：实现 `TtsSynthesizer` 并在组合根注入，不修改缓存和调用方。
- 调整弱网策略：修改 `GatewayConnectionPolicy`，不得把重试逻辑塞进音频/意图处理代码。

## 迁移说明

`GatewayClient` 中旧的 audio/chat/TTS/turn 业务发送与 payload 解析方法已经物理删除。协议测试
通过测试侧协议 helper 驱动通用帧接口。旧 app `TtsCache` 与 `SpeechOutputService` 已删除，
生产装配、缓存和播放全链路均由 `:tts` 提供。
