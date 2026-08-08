package com.autovoice.voicecore

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContractSmokeTest {
    @Test
    fun `shared fixtures readable`() {
        val path = javaClass.classLoader.getResource("gateway-reply-action.json")
        assertNotNull(path, "shared/fixtures 未接线")
        val text = path!!.readText()
        assertTrue(text.contains("\"type\": \"reply\""))
    }
}
