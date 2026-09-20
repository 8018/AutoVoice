# audio-frontend

麦克风采集后的统一音频前端模块，负责两类原子能力及其组合：

- `FrontendSignalProcessor`：软件前端信号处理接口；当前实现为 RNNoise。
- `CaptureEffects`：绑定 `AudioRecord` session 的硬件/系统 AEC 与降噪资源。
- `VadEngine`：逐帧语音活动检测接口；当前实现为 Silero VAD。
- `AudioFrontendEngine`：一次接收原始 PCM，同时产出处理后 PCM、VAD 事件和语音切段。

`app` 只管理 `AudioRecord` 生命周期并消费 `AudioFrontendEngine` 输出，不直接创建或编排
RNNoise、Silero、VAD 门控和切段器。ASR/NLU、仲裁、会话状态与业务处理均不属于本模块。
