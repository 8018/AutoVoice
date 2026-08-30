package com.autovoice.voicecore.arbiter

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PendingSignalRegistryTest {
    @Test
    fun `pending is delivered only to its own turn`() = runBlocking {
        val registry = PendingSignalRegistry()
        val first = registry.channel("turn-1")
        val second = registry.channel("turn-2")

        registry.signal("turn-2")

        assertNull(withTimeoutOrNull(20) { first.receive() })
        assertNotNull(withTimeoutOrNull(100) { second.receive() })
    }
}
