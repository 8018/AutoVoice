# 生产装配提取（第五阶段）

生产入口迁到 VoiceEngineFactory.create，负责厂商 SDK、本地 ASR/NLU、网关、
仲裁器、遥测、TTS 缓存和回调绑定。MainViewModel 通过工厂构建引擎。
VoiceEngine 保留运行时音频入口、结果路由、业务执行及协调器接线。

工厂通过模块内事件入口传递 ASR 和 realtime 回复；缓存遥测通过引擎方法记录，
不向工厂暴露可写的播放轮次字段。原有配置、超时和协议沿用。

本阶段为保持行为的提取，使用已有 VoiceEngine、GatewayBridge、播放、录音和
对话控制器回归测试验证接线，并执行 Debug 构建与 lint。
