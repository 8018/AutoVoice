package com.autovoice.voicecore

/** Original request-owner facts. All callbacks are nonblocking and never consult a newer input. */
data class LocalRequestAdmission(val deadlineMs: Long, val finalized: () -> Boolean,
    val originalPermitValid: () -> Boolean, val ownerLive: () -> Boolean) {
    fun valid(nowMs: Long): Boolean = ownerLive() && nowMs < deadlineMs && (finalized() || originalPermitValid())
}
fun interface LocalRequestAdmissionProvider { fun capture(requestId: String): LocalRequestAdmission? }
