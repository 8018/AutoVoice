package com.autovoice.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TravelDocumentInterceptorTest {
    @Test fun `frames require business admission and stream in order`() {
        val gate = TravelDocumentInterceptor()
        assertFalse(gate.accept("u-1", "s-1", "start", ""))
        gate.admit("u-1", "s-1")
        assertFalse(gate.accept("u-1", "s-1", "delta", "early"))
        assertTrue(gate.accept("u-1", "s-1", "start", ""))
        assertTrue(gate.accept("u-1", "s-1", "delta", "# 北京"))
        assertTrue(gate.accept("u-1", "s-1", "complete", ""))
        assertFalse(gate.accept("u-1", "s-1", "delta", "late"))
    }

    @Test fun `new admitted turn retires previous document and blocks stale chunks`() {
        val gate = TravelDocumentInterceptor(maxChars = 5)
        gate.admit("u-1", "s-1")
        assertTrue(gate.accept("u-1", "s-1", "start", ""))
        assertFalse(gate.accept("u-1", "wrong", "delta", "x"))
        assertFalse(gate.accept("u-1", "s-1", "delta", "123456"))
        assertTrue(gate.accept("u-1", "s-1", "delta", "12345"))
        assertNull(gate.retireIfDifferent("u-1"))
        assertEquals("u-1" to "s-1", gate.retireIfDifferent("u-2"))
        assertFalse(gate.accept("u-1", "s-1", "delta", "x"))
    }
}
