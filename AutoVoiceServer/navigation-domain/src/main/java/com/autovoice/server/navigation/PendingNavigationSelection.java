package com.autovoice.server.navigation;

import java.util.List;

record PendingNavigationSelection(
        String selectionId,
        List<NavigationCandidate> candidates,
        long createdAtMs,
        long expiresAtMs,
        boolean adopted) {

    PendingNavigationSelection {
        candidates = List.copyOf(candidates);
    }

    PendingNavigationSelection(String selectionId, List<NavigationCandidate> candidates,
                               long createdAtMs, long expiresAtMs) {
        this(selectionId, candidates, createdAtMs, expiresAtMs, true); // D05b 兼容默认:已采用
    }

    PendingNavigationSelection withAdopted(boolean adopted) {
        return new PendingNavigationSelection(selectionId, candidates, createdAtMs, expiresAtMs, adopted);
    }
}
