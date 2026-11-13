package com.autovoice.app

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LocationQualityTest {
    @Test fun `saving an address requires a more recent and accurate fix`() {
        assertTrue(LocationQuality.usableForSavedAddress(30_000, 50f))
        assertFalse(LocationQuality.usableForSavedAddress(30_001, 5f))
        assertFalse(LocationQuality.usableForSavedAddress(1_000, 51f))
        assertFalse(LocationQuality.usableForSavedAddress(-1, 5f))
        assertFalse(LocationQuality.usableForSavedAddress(1_000, Float.NaN))
    }

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
