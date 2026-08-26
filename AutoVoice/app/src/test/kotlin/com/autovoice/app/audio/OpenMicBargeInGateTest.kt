package com.autovoice.app.audio

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OpenMicBargeInGateTest {

    @Test
    fun `disabled gate never triggers`() {
        val gate = OpenMicBargeInGate()

        repeat(20) { assertFalse(gate.feed(0.99f)) }
    }

    @Test
    fun `requires sustained speech and triggers once per playback`() {
        val gate = OpenMicBargeInGate()
        gate.start()

        repeat(4) { assertFalse(gate.feed(0.9f)) }
        assertTrue(gate.feed(0.9f))
        assertFalse(gate.feed(0.9f))

        gate.start()
        repeat(4) { assertFalse(gate.feed(0.9f)) }
        assertTrue(gate.feed(0.9f))
    }

    @Test
    fun `cold frame resets speech confirmation`() {
        val gate = OpenMicBargeInGate()
        gate.start()

        repeat(4) { assertFalse(gate.feed(0.9f)) }
        assertFalse(gate.feed(0.1f))
        repeat(4) { assertFalse(gate.feed(0.9f)) }
        assertTrue(gate.feed(0.9f))
    }
}
