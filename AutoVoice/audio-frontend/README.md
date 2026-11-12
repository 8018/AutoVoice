# audio-frontend

公共音频契约与门控模块。保留 `AudioFrontendEngine`、`FrontendSignalProcessor`、
`CaptureEffects`、`VadEngine`，以及公共分段和门控逻辑；不包含具体算法、模型、ORT 或 native 构建。

App 通过 `AudioFrontendEngineFactory`、`VadEngineFactory`、`CaptureEffectsFactory` 选择实现。
模式重配复用所选 factory；续听与打断使用独立于正式分段的 VAD。
旧实现位于 `audio-frontend-legacy`。
