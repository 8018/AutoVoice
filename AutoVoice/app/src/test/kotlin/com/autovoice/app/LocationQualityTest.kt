package com.autovoice.app

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LocationQualityTest {
    @Test fun `accept recent accurate fixes`() {
        assertTrue(LocationQuality.usable(0, 10f))
        assertTrue(LocationQuality.usable(120_000, 500f))
    }
    @Test fun `reject stale future and imprecise fixes`() {
        assertFalse(LocationQuality.usable(120_001, 10f))
        assertFalse(LocationQuality.usable(-1, 10f))
        assertFalse(LocationQuality.usable(0, 501f))
        assertFalse(LocationQuality.usable(0, Float.NaN))
        assertFalse(LocationQuality.usable(0, -1f))
    }
}
