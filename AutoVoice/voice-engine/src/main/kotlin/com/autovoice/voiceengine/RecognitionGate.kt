package com.autovoice.voiceengine

import com.autovoice.voiceengine.api.RecognitionControl
import java.util.UUID

/** Input admission only. It deliberately does not inspect the DM turn or suppress completed NLU. */
class RecognitionGate : RecognitionControl {
    private val lock = Any()
    private val instanceId = UUID.randomUUID().toString()
    private var generation = 0L
    private var enabled = false

    override val recognitionEnabled: Boolean get() = synchronized(lock) { enabled }

    override fun startRecognition() = synchronized(lock) {
        if (!enabled) {
            enabled = true
            generation++
        }
    }

    override fun stopRecognition() = synchronized(lock) {
        if (enabled) {
            enabled = false
            generation++
        }
    }

    /** Capture a permit at the input boundary; null means recognition is closed. */
    fun admitInput(): InputPermit? = synchronized(lock) {
        if (enabled) InputPermit(instanceId, generation) else null
    }

    /** Check again immediately before passing queued audio to an internal ASR or upload engine. */
    fun accepts(permit: InputPermit?): Boolean = synchronized(lock) {
        permit != null && enabled && permit.instanceId == instanceId && permit.generation == generation
    }

    /** Opaque to callers: a permit from a previous engine instance or enable cycle is never valid. */
    class InputPermit internal constructor(internal val instanceId: String, internal val generation: Long)
}
