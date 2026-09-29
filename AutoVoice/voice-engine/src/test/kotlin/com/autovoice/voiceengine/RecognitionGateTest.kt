package com.autovoice.voiceengine

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecognitionGateTest {
    @Test
    fun `initially disabled and invalidates old input across disable and reenable`() {
        val gate = RecognitionGate()
        assertNull(gate.admitInput())
        gate.startRecognition()
        val first = gate.admitInput()
        assertTrue(gate.accepts(first))
        gate.stopRecognition()
        assertFalse(gate.accepts(first))
        assertNull(gate.admitInput())
        gate.startRecognition()
        assertFalse(gate.accepts(first))
        assertTrue(gate.accepts(gate.admitInput()))
    }

    @Test
    fun `permit cannot be reused by a replacement engine`() {
        val first = RecognitionGate().apply { startRecognition() }
        val second = RecognitionGate().apply { startRecognition() }
        assertFalse(second.accepts(first.admitInput()))
    }
}
