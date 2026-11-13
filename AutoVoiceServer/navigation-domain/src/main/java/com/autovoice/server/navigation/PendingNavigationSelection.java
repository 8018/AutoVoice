package com.autovoice.server.navigation;

import java.util.List;
import com.autovoice.server.contracts.NavigationRoutePreview.Preference;

record PendingNavigationSelection(
        String selectionId,
        List<NavigationCandidate> candidates,
        long createdAtMs,
        long expiresAtMs,
        boolean adopted,
        String searchState,
        Preference previewPreference) {

    PendingNavigationSelection {
        candidates = List.copyOf(candidates);
    }

    PendingNavigationSelection(String selectionId, List<NavigationCandidate> candidates,
                               long createdAtMs, long expiresAtMs, boolean adopted) {
        this(selectionId, candidates, createdAtMs, expiresAtMs, adopted, null, null);
    }

    PendingNavigationSelection(String selectionId, List<NavigationCandidate> candidates,
                               long createdAtMs, long expiresAtMs) {
        this(selectionId, candidates, createdAtMs, expiresAtMs, false);
    }

    PendingNavigationSelection withAdopted(boolean adopted) {
        return new PendingNavigationSelection(selectionId, candidates, createdAtMs, expiresAtMs, adopted, searchState, previewPreference);
    }
}
