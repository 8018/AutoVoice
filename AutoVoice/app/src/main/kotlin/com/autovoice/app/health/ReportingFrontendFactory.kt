package com.autovoice.app.health

import com.autovoice.audiofrontend.*
import com.autovoice.voicecore.health.*

/** Construction failure is an explicit unusable frontend; never passthrough audio. */
internal fun ChannelComponentReporting.safeFrontendFactory(delegate: AudioFrontendEngineFactory) = AudioFrontendEngineFactory { context, vad, ecnr ->
    try { delegate.create(context, vad, ecnr) }
    catch (error: Throwable) {
        error.rethrowCancellationOrFatal()
        val reporter = reporter("frontend")
        val failure = initializationError(error)
        val token = reporter.start("native_load")
        reporter.report(token, ComponentUpdate.failed(failure))
        object : AudioFrontendEngine {
            private var boundReporter = reporter
            private var boundToken = token
            override val unavailabilityReason = failure.message
            override val vadAvailable = false
            override val diagnostics = AudioFrontendDiagnostics()
            override fun attachReporting(frontendReporter: ComponentReporter, vadReporter: ComponentReporter) {
                boundReporter = frontendReporter
                boundToken = frontendReporter.start("reuse_existing")
                frontendReporter.report(boundToken, ComponentUpdate.failed(failure))
            }
            override fun startTurn(): Unit = throw IllegalStateException("audio frontend unavailable")
            override fun process(block: ByteArray): AudioFrontendFrame = throw IllegalStateException("audio frontend unavailable")
            override fun finishProcessedAudio() = ByteArray(0)
            override fun finishSegments() = emptyList<ByteArray>()
            override fun close() { boundReporter.close(boundToken) }
        }
    }
}
