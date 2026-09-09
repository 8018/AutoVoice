# 网关适配提取：第一阶段

## 文件边界

- VoiceEngine.kt：继续承担现有装配、准入、状态机与业务/播放接线。
- VoiceGatewayPorts.kt：TtsRequester、StreamingCloudRunner、RealtimeChatRunner 接口。
- GatewayAdapter.kt：GatewayCloudRunner、GatewayBridge 和网关专用常量/异常。

保持 com.autovoice.app 包名及既有接口签名，避免同时迁移 Gradle 模块或修改协议。
GatewayCloudRunner 与 RealtimeChatRunner 从文件私有调整为模块内可见；
其他声明保留原可见性。网关实现内容保持不变，现有 GatewayBridgeTest 无需改测试路径。

## 行为边界

本次不改连接恢复、消息对账、上传分块、pending、ASR 事件、TTS 请求、
S2S 音频、回复文本放行策略、超时或仲裁。GatewayAdapter 仍保留原有回调约定。
这只是第一步源码职责提取，不宣称已完成对话控制器、播放协调器或录音生命周期重构。

## 验证

逐段对比提取前后网关实现，除必需的类可见性调整外一致。
验证命令：在 AutoVoice 执行 ./gradlew test :app:assembleDebug :app:lintDebug。
覆盖既有 GatewayBridge 协议对账、VoiceEngine 接线及 gateway-client 测试。
本次不涉及服务端部署；真机安装验证另行进行。
