package com.autovoice.business

import com.autovoice.voicecore.Intent

/**
 * Boundary between the voice pipeline and product business capabilities.
 *
 * The caller has already completed turn validation and arbitration. Implementations own domain
 * routing and side effects; the voice engine never needs to know about navigation, climate, or a
 * concrete vehicle state.
 */
fun interface BusinessHandler {
    fun handle(command: BusinessCommand): BusinessResult
}

data class BusinessCommand(
    val turnId: String,
    val intent: Intent,
)

data class BusinessResult(
    val status: Status,
    val speakText: String? = null,
) {
    enum class Status { APPLIED, FAILED, DUPLICATE, REJECTED }

    companion object {
        fun applied(speakText: String? = null) = BusinessResult(Status.APPLIED, speakText)
        fun failed() = BusinessResult(Status.FAILED)
        fun rejected() = BusinessResult(Status.REJECTED)
    }
}

/** Domain registry used by the application composition root. */
class BusinessRouter(
    handlers: Map<String, BusinessHandler>,
    private val fallback: BusinessHandler = BusinessHandler { BusinessResult.rejected() },
) : BusinessHandler {
    private val handlers = handlers.toMap()

    override fun handle(command: BusinessCommand): BusinessResult =
        handlers[command.intent.domain]?.handle(command) ?: fallback.handle(command)
}
