package com.autovoice.app

/**
 * Pure navigation-task policy. It validates proposals against the immutable list displayed by
 * the client; it never starts an activity, changes dialogue state or consumes server context.
 */
internal class NavigationDialoguePolicy {
    fun acceptsOffer(
        selectionId: String?,
        candidates: List<NavigationExecutor.NavigationCandidate>,
    ): Boolean = candidates.isNotEmpty() && (
        selectionId == null || (
            selectionId.isNotBlank() &&
                candidates.all { it.candidateId.isNotBlank() } &&
                candidates.map { it.candidateId }.toSet().size == candidates.size
            )
        )

    fun matchSelection(
        snapshot: NavigationSnapshot,
        selectionId: String?,
        candidateId: String?,
        target: NavigationTarget,
        hasWaypoints: Boolean,
    ): NavigationExecutor.NavigationCandidate? {
        if (selectionId == null || selectionId != snapshot.selectionId || candidateId.isNullOrBlank()) {
            return null
        }
        if (hasWaypoints) return null
        return snapshot.candidates.singleOrNull {
            it.candidateId == candidateId && it.poiname == target.name &&
                it.lat == target.latitude && it.lon == target.longitude
        }
    }
}
