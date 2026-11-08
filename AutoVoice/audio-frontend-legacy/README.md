# audio-frontend-legacy

原音频前端的默认组合：RNNoise、Silero VAD 与 Android 系统采集效果。
实现保留原包名以兼容调用方与 RNNoise JNI 符号。`AudioFrontendFactory` 属于本模块，
公共 `audio-frontend` 不依赖本模块。

处理顺序仍是原始 512-sample PCM 先送 Silero/分段，再按 480-sample 网格送 RNNoise。
尾帧补零处理后截回有效样本数；关闭 RNNoise 时返回原始 PCM 副本。
Silero 模型不可用时 VAD 不产生事件或分段，信号处理保持原有行为。

RNNoise 源码与许可证一同迁入 `src/main/cpp`；Silero 模型迁入 `src/main/assets`，
库 assets 自动并入 App。真实模型与分段测试随模型迁入本模块，使用原语音 fixture。
