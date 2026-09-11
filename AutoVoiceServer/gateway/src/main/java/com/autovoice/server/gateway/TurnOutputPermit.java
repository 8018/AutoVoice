package com.autovoice.server.gateway;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A turn-scoped output capability owned by the session layer.
 *
 * <p>Revocation suppresses every user-visible output for this turn and wakes the connection
 * worker so a newer admitted turn can proceed. It deliberately does not cancel ASR, NLU, LLM,
 * tool, or arbitration futures: those producers may finish naturally and remain observable in
 * telemetry, while their output sinks keep consulting this immutable turn handle.</p>
 */
final class TurnOutputPermit {

    enum RevocationReason { CANCELLED, SUPERSEDED, CONNECTION_CLOSED }

    private final String utteranceId;
    private final String segmentId;
    private final AtomicReference<RevocationReason> reason = new AtomicReference<>();
    private final CompletableFuture<RevocationReason> revoked = new CompletableFuture<>();

    TurnOutputPermit(String utteranceId, String segmentId) {
        this.utteranceId = utteranceId;
        this.segmentId = segmentId;
    }

    String utteranceId() {
        return utteranceId;
    }

    String segmentId() {
        return segmentId;
    }

    boolean allowsOutput() {
        return reason.get() == null;
    }

    boolean revoke(RevocationReason revocationReason) {
        if (!reason.compareAndSet(null, revocationReason)) return false;
        revoked.complete(revocationReason);
        return true;
    }

    CompletableFuture<RevocationReason> revoked() {
        return revoked;
    }

    RevocationReason reason() {
        return reason.get();
    }
}
