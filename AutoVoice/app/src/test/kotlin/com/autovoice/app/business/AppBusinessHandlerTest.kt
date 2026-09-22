package com.autovoice.app.business

import com.autovoice.app.MockVehicleState
import com.autovoice.business.BusinessCommand
import com.autovoice.business.BusinessResult
import com.autovoice.voicecore.Intent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AppBusinessHandlerTest {
    @Test fun `exit dialogue leaves chat and invokes local lifecycle exit without speech`() {
        val modes = mutableListOf<Boolean>()
        var exits = 0
        val handler = AppBusinessHandler(
            vehicle = MockVehicleState(),
            navigation = null,
            onConversationMode = modes::add,
            onExitDialogue = { exits++ },
        )
        val result = handler.handle(BusinessCommand("turn", Intent(
            schemaVersion = "1.0", domain = "conversation", intent = "exit_dialogue",
            slots = emptyMap(), confidence = 1.0, source = "rule.nlu",
        )))

        assertEquals(BusinessResult.Status.APPLIED, result.status)
        assertEquals(null, result.speakText)
        assertEquals(listOf(false), modes)
        assertEquals(1, exits)
    }
}
