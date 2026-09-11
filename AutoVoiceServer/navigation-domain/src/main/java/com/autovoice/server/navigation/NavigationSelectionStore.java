package com.autovoice.server.navigation;

import java.util.Optional;

interface NavigationSelectionStore {
    Optional<PendingNavigationSelection> find(String logicalSessionId);
    void put(String logicalSessionId, PendingNavigationSelection selection);
    boolean remove(String logicalSessionId, PendingNavigationSelection expected);
}
