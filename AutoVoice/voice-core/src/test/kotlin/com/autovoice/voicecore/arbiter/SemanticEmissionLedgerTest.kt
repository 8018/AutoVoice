package com.autovoice.voicecore.arbiter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SemanticEmissionLedgerTest {
    @Test
    fun `each turn emits semantic at most once without a current-turn concept`() {
        val ledger = SemanticEmissionLedger()

        assertEquals(SemanticEmissionResult.ACCEPTED, ledger.tryEmit("old-but-still-arriving"))
        assertEquals(
            SemanticEmissionResult.TURN_ALREADY_EMITTED,
            ledger.tryEmit("old-but-still-arriving"),
        )
        assertEquals(SemanticEmissionResult.ACCEPTED, ledger.tryEmit("another-turn"))
    }
}
