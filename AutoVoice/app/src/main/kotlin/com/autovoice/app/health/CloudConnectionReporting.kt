package com.autovoice.app.health

import com.autovoice.voicecore.health.*

internal enum class CloudConnectionOutcome { STARTED, READY, FAILED, LOST }
internal data class CloudConnectionDiagnostic(val attempt: Long, val outcome: CloudConnectionOutcome)

/** Diagnostic ownership is independent of the business ready-before-failure latch. */
internal class CloudConnectionReporting(private val reporter: ComponentReporter) : AutoCloseable {
    private var attempt = 0L
    private var token: ComponentToken? = null
    private var closed = false
    private var initialized = false
    @Synchronized fun accept(event: CloudConnectionDiagnostic) {
        if (closed || event.attempt < attempt) return
        if (event.outcome == CloudConnectionOutcome.STARTED) {
            if (event.attempt <= attempt) return
            attempt = event.attempt
            if (!initialized) token = reporter.start("connect")
            else token?.let { reporter.report(it,ComponentUpdate.health(Health.UNKNOWN)) }
            return
        }
        if (event.attempt != attempt) return
        token?.let { reporter.report(it,when (event.outcome) {
            CloudConnectionOutcome.READY -> if (initialized) ComponentUpdate.health(Health.HEALTHY)
                else ComponentUpdate.ready().also { initialized = true }
            CloudConnectionOutcome.FAILED -> if (initialized) ComponentUpdate.health(Health.UNAVAILABLE,ComponentError(ErrorCode.COMPONENT_RUNTIME_FAILED,retryable = true))
                else ComponentUpdate.failed(ComponentError(ErrorCode.COMPONENT_RUNTIME_FAILED,retryable = true))
            CloudConnectionOutcome.LOST -> ComponentUpdate.health(Health.UNAVAILABLE,ComponentError(ErrorCode.COMPONENT_RUNTIME_FAILED,retryable = true))
            else -> return
        }) }
    }
    @Synchronized override fun close() { if (!closed) { closed = true;reporter.close() } }
}
