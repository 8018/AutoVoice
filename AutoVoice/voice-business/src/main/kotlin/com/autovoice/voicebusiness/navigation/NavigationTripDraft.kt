package com.autovoice.voicebusiness.navigation

/** A resolved stop retains its original position in the requested trip. */
data class NavigationDraftStop internal constructor(
    val query: String,
    val target: NavigationTarget?,
    internal val choices: List<NavigationExecutor.NavigationCandidate> = emptyList(),
    internal val savedKind: SavedPlaceKind? = null,
    internal val savedValue: SavedNavigationPlace? = null,
)

data class NavigationTripDraft internal constructor(
    val stops: List<NavigationDraftStop>,
    val revision: Long,
    val previewPreference: RoutePreference? = null,
) {
    val nextStopIndex: Int? get() = stops.indexOfFirst { it.target == null }.takeIf { it >= 0 }
    val isReady: Boolean get() = nextStopIndex == null

    internal fun select(candidateId: String): NavigationTripDraft? {
        val index = nextStopIndex ?: return null
        val stop = stops[index]
        val candidate = stop.choices.singleOrNull { it.candidateId == candidateId } ?: return null
        val resolved = stop.copy(target = NavigationTarget(candidate.poiname, candidate.lat, candidate.lon))
        return copy(stops = stops.toMutableList().apply { set(index, resolved) })
    }

    internal fun trip(): NavigationTrip? {
        if (!isReady) return null
        val ordered = stops.map { checkNotNull(it.target) }
        return NavigationTrip(ordered.last(), ordered.dropLast(1))
    }

    internal fun prompt(): String {
        val index = nextStopIndex
        if (index != null) {
            val stop = stops[index]
            val choices = stop.choices.mapIndexed { i, choice ->
                val address = choice.address.takeIf(String::isNotBlank)?.let { "，$it" }.orEmpty()
                val evidence = choice.matchSummary.takeIf(String::isNotBlank)?.let { "，$it" }.orEmpty()
                "第${i + 1}个，${choice.poiname}$address$evidence"
            }
            return "请选择第${index + 1}站${stop.query}。${choices.joinToString("；")}。"
        }
        val trip = checkNotNull(trip())
        val action = if (previewPreference == null) "请确认或放弃行程" else "请确认预览或放弃行程"
        return "途经${trip.waypoints.joinToString("、") { it.name }}，终点是${trip.destination.name}。${action}。"
    }

    companion object {
        const val MAX_STOPS = 5
    }
}
