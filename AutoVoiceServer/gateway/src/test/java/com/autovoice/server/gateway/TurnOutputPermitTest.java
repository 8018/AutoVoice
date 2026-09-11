package com.autovoice.server.gateway;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TurnOutputPermitTest {

    @Test
    void revocationIsIdempotentAndWakesOnlyThisTurn() {
        TurnOutputPermit oldTurn = new TurnOutputPermit("u-old", "s-old");
        TurnOutputPermit currentTurn = new TurnOutputPermit("u-current", "s-current");

        assertTrue(oldTurn.allowsOutput());
        assertTrue(currentTurn.allowsOutput());
        assertTrue(oldTurn.revoke(TurnOutputPermit.RevocationReason.SUPERSEDED));

        assertFalse(oldTurn.allowsOutput());
        assertEquals(TurnOutputPermit.RevocationReason.SUPERSEDED, oldTurn.revoked().join());
        assertTrue(currentTurn.allowsOutput(), "旧轮撤销不得影响新轮");
        assertFalse(oldTurn.revoke(TurnOutputPermit.RevocationReason.CANCELLED));
        assertEquals(TurnOutputPermit.RevocationReason.SUPERSEDED, oldTurn.reason());
    }
}
