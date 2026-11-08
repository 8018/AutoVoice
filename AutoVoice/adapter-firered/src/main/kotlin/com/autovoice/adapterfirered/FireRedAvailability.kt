package com.autovoice.adapterfirered

import com.autovoice.voicecore.AsrEngine

/** Real runtime/model delivery is a later task. This boundary cannot emit a synthetic final. */
object FireRedAvailability {
    const val UNAVAILABLE_REASON = "FireRedASR2 CTC INT8 runtime and model bundle are not installed"
    fun createUnavailable(): AsrEngine = AsrEngine { _, _, _ -> throw IllegalStateException(UNAVAILABLE_REASON) }
}
