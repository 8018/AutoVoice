# 客户端通信、TTS 与业务边界

## 目标依赖方向

```text
Recording / UI
      |
      v
 VoiceEngine  ----> TtsService (:tts)
      |                 |-- cache（内部）
      |                 `-- synthesizer port（内部选择）
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
6. `TtsService` 是合成音频的唯一入口。缓存 key、磁盘格式、命中策略和生成实现不向调用方暴露；
   Android 播放驱动仍作为输出 adapter，由既有播放身份机制保护迟到回调。

## 新业务接入

- 新增业务域：在 app 组合根注册新的 `BusinessHandler`，不修改 `VoiceEngine`。
- 新增网关消息：在桥中注册该消息类型的监听器，不修改 WebSocket 连接类。
- 替换 TTS 厂商：实现 `TtsSynthesizer` 并在组合根注入，不修改缓存和调用方。
- 调整弱网策略：修改 `GatewayConnectionPolicy`，不得把重试逻辑塞进音频/意图处理代码。

## 迁移说明

`GatewayClient` 内旧的业务发送与解析方法暂时保留为 `internal + Deprecated`，只供该模块的既有
协议回归测试编译；app 生产代码已无调用。后续把这些测试迁到独立协议测试后可物理删除旧方法。
app 内旧 `TtsCache` 同样只为迁移期测试保留为 `internal + Deprecated`，生产装配使用 `:tts`。
