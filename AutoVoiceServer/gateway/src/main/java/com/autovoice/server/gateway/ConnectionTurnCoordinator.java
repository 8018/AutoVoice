package com.autovoice.server.gateway;

import java.util.Objects;

/**
 * Owns the bounded per-connection processing slot. Audio candidates may queue behind the current
 * turn, but only an explicit admission event may supersede that current turn.
 */
final class ConnectionTurnCoordinator<W extends ConnectionTurnCoordinator.WorkIdentity> {

    interface WorkIdentity {
        String utteranceId();
        String segmentId();
    }

    enum Offer { START_NOW, QUEUED, REJECTED }

    private W processing;
    private W queued;

    synchronized Offer offer(W work) {
        Objects.requireNonNull(work, "work");
        if (processing == null) {
            processing = work;
            return Offer.START_NOW;
        }
        if (queued == null) {
            queued = work;
            return Offer.QUEUED;
        }
        return Offer.REJECTED;
    }

    /** Completes exactly the supplied work and promotes the queued candidate, if any. */
    synchronized W complete(W work) {
        if (processing != work) return null;
        processing = queued;
        queued = null;
        return processing;
    }

    synchronized W processing() {
        return processing;
    }

    synchronized W queued() {
        return queued;
    }

    synchronized boolean ownsSegment(String segmentId) {
        if (segmentId == null) return false;
        return processing != null && segmentId.equals(processing.segmentId())
                || queued != null && segmentId.equals(queued.segmentId());
    }

    synchronized W removeQueued(String segmentId) {
        if (queued == null || segmentId == null || !segmentId.equals(queued.segmentId())) return null;
        W removed = queued;
        queued = null;
        return removed;
    }

    synchronized void clear() {
        processing = null;
        queued = null;
    }
}
