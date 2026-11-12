package com.autovoice.adapterwebrtc

import com.autovoice.audiofrontend.*
import com.autovoice.voicecore.VadConfig
import com.autovoice.voicecore.health.*

/** Production construction has no passthrough or synthetic success path. */
object WebRtcFrontend {
    const val UNAVAILABLE_REASON = "WebRTC APM/VAD native implementation is not installed"
    fun validateVadConfig(config: VadConfig) {
        require(config.provider == "webrtc.vad") { "config: unsupported vad.provider for WebRTC" }
        require(!config.thresholdProvided && config.threshold == VadConfig.DEFAULT_THRESHOLD) { "config: Open rejects legacy vad.threshold" }
        require(config.aggressiveness in 0..3) { "config: vad.aggressiveness must be 0..3" }
    }
    fun create(config: VadConfig, frontendReporter: ComponentReporter = ComponentReporter.none(),
        vadReporter: ComponentReporter = ComponentReporter.none(), auxVadReporter: ComponentReporter = ComponentReporter.none(),
        aecReporter: ComponentReporter = ComponentReporter.none()): AudioFrontendEngine =
        createWithBackend(config,frontendReporter,vadReporter,auxVadReporter,aecReporter) {
            SingleThreadWebRtcBackend { JniWebRtcBackend(config.aggressiveness) }
        }
    internal fun createWithBackend(config: VadConfig, frontendReporter: ComponentReporter = ComponentReporter.none(),
        vadReporter: ComponentReporter = ComponentReporter.none(), auxVadReporter: ComponentReporter = ComponentReporter.none(),
        aecReporter: ComponentReporter = ComponentReporter.none(), backendFactory: () -> WebRtcBackend): AudioFrontendEngine {
        validateVadConfig(config)
        val token = frontendReporter.start("native_load")
        var backend: WebRtcBackend? = null
        return try {
            backend = backendFactory()
            WebRtcAudioFrontend(backend,config,frontendReporter = frontendReporter,vadReporter = vadReporter,
                auxVadReporter = auxVadReporter,aecReporter = aecReporter)
        } catch (error: Throwable) {
            error.rethrowCancellationOrFatal()
            backend?.close()
            val failure = initializationError(error)
            frontendReporter.report(token,ComponentUpdate.failed(failure))
            unavailable(failure.message,frontendReporter,token,failure)
        }
    }
    fun createUnavailable(): AudioFrontendEngine = unavailable(UNAVAILABLE_REASON)
    private fun unavailable(reason: String, initialReporter: ComponentReporter? = null, initialToken: ComponentToken? = null,
        failure: ComponentError = ComponentError(ErrorCode.NATIVE_LIBRARY_MISSING)): AudioFrontendEngine = object : AudioFrontendEngine {
        private var reporter = initialReporter
        private var token = initialToken
        override val continuousProcessing = true
    override val ownsAuxiliaryVad = true
        override val unavailabilityReason = reason
        override val vadAvailable = false
        override val diagnostics = AudioFrontendDiagnostics()
        override fun attachReporting(frontendReporter: ComponentReporter, vadReporter: ComponentReporter) {
            reporter = frontendReporter;token = frontendReporter.start("reuse_existing")
            frontendReporter.report(requireNotNull(token),ComponentUpdate.failed(failure))
        }
        override fun startTurn(): Unit = throw IllegalStateException(reason)
        override fun process(block: ByteArray): FrontendBatch = throw IllegalStateException(reason)
        override fun finishProcessedAudio() = ByteArray(0)
        override fun finishSegments() = emptyList<ByteArray>()
        override fun close() { token?.let { reporter?.close(it) } }
    }
}
