package com.autovoice.voicecore.dialog

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TurnAdmissionGateTest {
    @Test
    fun `blank asr does not admit false vad`() {
        val gate = TurnAdmissionGate()
        gate.open("capture")

        assertNull(gate.confirmText("capture", "  ", AdmissionEvidence.LOCAL_ASR))
        assertTrue(gate.reject("capture"))
    }

    @Test
    fun `first evidence admits once and stale capture cannot overwrite it`() {
        val gate = TurnAdmissionGate()
        gate.open("capture")

        val admitted = gate.confirmText("capture", "去机场", AdmissionEvidence.CLOUD_ASR)
        assertEquals(AdmittedTurn("capture", AdmissionEvidence.CLOUD_ASR), admitted)
        assertEquals(admitted, gate.confirmSemantic("capture", AdmissionEvidence.CLOUD_FINAL_SEMANTIC))
        assertFalse(gate.reject("capture"))

        gate.open("next")
        assertNull(gate.confirmSemantic("capture", AdmissionEvidence.CLOUD_FINAL_SEMANTIC))
    }
}
