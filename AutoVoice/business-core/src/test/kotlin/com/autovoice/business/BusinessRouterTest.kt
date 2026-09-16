package com.autovoice.business

import com.autovoice.voicecore.Intent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BusinessRouterTest {
    @Test
    fun `routes by domain and rejects unknown domain`() {
        val router = BusinessRouter(mapOf("navigation" to BusinessHandler { BusinessResult.applied("ok") }))

        assertEquals(BusinessResult.applied("ok"), router.handle(command("navigation")))
        assertEquals(BusinessResult.rejected(), router.handle(command("weather")))
    }

    private fun command(domain: String) = BusinessCommand(
        "turn-1",
        Intent("1.0", domain, "test", emptyMap(), 1.0, "test"),
    )
}
