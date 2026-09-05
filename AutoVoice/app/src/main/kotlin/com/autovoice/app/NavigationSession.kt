package com.autovoice.app

/** A handoff records an accepted launch, not active guidance in the other application. */
data class NavigationTarget(val name: String, val latitude: Double, val longitude: Double) {
    init {
        require(name.isNotBlank())
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
    }
}

data class NavigationTrip(
    val destination: NavigationTarget,
    val waypoints: List<NavigationTarget> = emptyList(),
)

enum class NavigationHandoff { NONE, OPENING, ACCEPTED, FAILED }

data class NavigationSnapshot(
    val candidateVersion: Long = 0,
    val selectionId: String? = null,
    val candidates: List<NavigationExecutor.NavigationCandidate> = emptyList(),
    val trip: NavigationTrip? = null,
    val handoff: NavigationHandoff = NavigationHandoff.NONE,
)

/** App-scoped navigation business state. Contains no speech turn or arbitration policy. */
class NavigationSession(private val publish: (NavigationSnapshot) -> Unit = {}) {
    @Volatile var snapshot = NavigationSnapshot()
        private set

    @Synchronized fun offer(candidates: List<NavigationExecutor.NavigationCandidate>, selectionId: String? = null) {
        update(snapshot.copy(candidateVersion = snapshot.candidateVersion + 1, candidates = candidates.toList(),
            selectionId = selectionId.takeIf { candidates.isNotEmpty() }))
    }

    /** An old dialog timer must not clear a newer result list. */
    @Synchronized fun expire(version: Long) {
        if (version == snapshot.candidateVersion && snapshot.candidates.isNotEmpty()) offer(emptyList())
    }

    /** Dismiss pending selection only; never pretend to stop external guidance. */
    @Synchronized fun cancelSelection(): Boolean {
        if (snapshot.candidates.isEmpty()) return false
        offer(emptyList())
        return true
    }

    @Synchronized fun beginHandoff(trip: NavigationTrip) {
        update(snapshot.copy(candidateVersion = snapshot.candidateVersion + 1, candidates = emptyList(),
            selectionId = null,
            trip = trip.copy(waypoints = trip.waypoints.toList()), handoff = NavigationHandoff.OPENING))
    }

    @Synchronized fun finishHandoff(accepted: Boolean) {
        update(snapshot.copy(handoff = if (accepted) NavigationHandoff.ACCEPTED else NavigationHandoff.FAILED))
    }

    private fun update(value: NavigationSnapshot) { snapshot = value; publish(value) }
}
