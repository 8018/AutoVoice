package com.autovoice.server.navigation;

import java.util.List;

record PendingNavigationSelection(
        String selectionId,
        List<NavigationCandidate> candidates,
        long createdAtMs,
        long expiresAtMs) {

    PendingNavigationSelection {
        candidates = List.copyOf(candidates);
    }
}
