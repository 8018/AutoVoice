package com.autovoice.server.gateway;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionTurnCoordinatorTest {
    private record Work(String utteranceId, String segmentId)
            implements ConnectionTurnCoordinator.WorkIdentity {}

    @Test
    void keepsCurrentWorkWhileOneCandidateWaits() {
        ConnectionTurnCoordinator<Work> turns = new ConnectionTurnCoordinator<>();
        Work current = new Work("u1", "s1");
        Work candidate = new Work("u2", "s2");

        assertEquals(ConnectionTurnCoordinator.Offer.START_NOW, turns.offer(current));
        assertEquals(ConnectionTurnCoordinator.Offer.QUEUED, turns.offer(candidate));
        assertSame(current, turns.processing());
        assertTrue(turns.ownsSegment("s2"));

        assertSame(candidate, turns.complete(current));
        assertSame(candidate, turns.processing());
        assertNull(turns.complete(current));
    }

    @Test
    void boundsTheCandidateQueueAndCanRemoveCancelledCandidate() {
        ConnectionTurnCoordinator<Work> turns = new ConnectionTurnCoordinator<>();
        turns.offer(new Work("u1", "s1"));
        Work queued = new Work("u2", "s2");
        assertEquals(ConnectionTurnCoordinator.Offer.QUEUED, turns.offer(queued));
        assertEquals(ConnectionTurnCoordinator.Offer.REJECTED, turns.offer(new Work("u3", "s3")));

        assertSame(queued, turns.removeQueued("s2"));
        assertFalse(turns.ownsSegment("s2"));
    }
}
