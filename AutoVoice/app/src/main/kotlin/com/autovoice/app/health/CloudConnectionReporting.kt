package com.autovoice.app.health

import com.autovoice.voicecore.health.*

internal enum class CloudConnectionOutcome { STARTED, READY, FAILED, LOST }
internal data class CloudConnectionDiagnostic(val attempt: Long, val outcome: CloudConnectionOutcome)

/** Diagnostic ownership is independent of the business ready-before-failure latch. */
internal class CloudConnectionReporting(private val reporter: ComponentReporter) : AutoCloseable {
    private var attempt = 0L
    private var token: ComponentToken? = null
    private var closed = false
    @Synchronized fun accept(event: CloudConnectionDiagnostic) {
        if (closed || event.attempt < attempt) return
        if (event.outcome == CloudConnectionOutcome.STARTED) {
            if (event.attempt <= attempt) return
            attempt = event.attempt;token = reporter.start("connect")
            return
        }
        if (event.attempt != attempt) return
        token?.let { reporter.report(it,when (event.outcome) {
            CloudConnectionOutcome.READY -> ComponentUpdate.ready()
            CloudConnectionOutcome.FAILED -> ComponentUpdate.failed(ComponentError(ErrorCode.COMPONENT_RUNTIME_FAILED,retryable = true))
            CloudConnectionOutcome.LOST -> ComponentUpdate.health(Health.UNAVAILABLE,ComponentError(ErrorCode.COMPONENT_RUNTIME_FAILED,retryable = true))
            else -> return
        }) }
    }
    @Synchronized override fun close() { if (!closed) { closed = true;reporter.close() } }
}
