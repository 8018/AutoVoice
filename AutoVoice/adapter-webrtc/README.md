# adapter-webrtc

Open 的 WebRTC APM/VAD 使用独立 JNI 库。WSL 构建、API33 JNI 探针和 App 合成音频路径已有验证。构建与 native 安装见 [Android 集成说明](../../docs/webrtc-firered-android-build.md)。缺少库时工厂公开 `unavailabilityReason` 并拒绝处理。物理 8295、声学、全车参考和发行信任仍待验收。
