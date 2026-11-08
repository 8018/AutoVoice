package com.autovoice.audiofrontend

import android.content.Context
import com.autovoice.audiofrontend.vad.VadEngine
import com.autovoice.voicecore.VadConfig

/** Creates turn processing using the provider selected by the application composition root. */
fun interface AudioFrontendEngineFactory {
    fun create(context: Context, vadConfig: VadConfig, ecnr: String): AudioFrontendEngine
}

/** Creates the independent speech detector shared by follow-up and barge-in listening. */
fun interface VadEngineFactory {
    fun create(context: Context): VadEngine?
}

/** Creates the selected provider's effects policy for the one microphone session. */
fun interface CaptureEffectsFactory {
    fun create(): CaptureEffects
}
