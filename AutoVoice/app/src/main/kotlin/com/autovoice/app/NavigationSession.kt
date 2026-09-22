package com.autovoice.app

import com.autovoice.voicecore.dialog.DialogueTask
import com.autovoice.voicecore.dialog.InputExpectation
import com.autovoice.voicecore.dialog.TaskDialogueCoordinator
import com.autovoice.voicecore.dialog.TaskEnd
import com.autovoice.voicecore.dialog.TaskEndReason
import com.autovoice.voicecore.dialog.TaskIdentity
import com.autovoice.voicecore.dialog.TaskStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

data class NavigationSelectionContext(
    val selectionId: String?,
    val candidates: List<NavigationExecutor.NavigationCandidate>,
    val selectedCandidateId: String? = null,
)

data class NavigationSnapshot(
    val candidateVersion: Long = 0,
    val taskId: String? = null,
    val interactionId: String? = null,
    val taskStatus: TaskStatus? = null,
    val selectionId: String? = null,
    val candidates: List<NavigationExecutor.NavigationCandidate> = emptyList(),
    val expectation: InputExpectation? = null,
    val trip: NavigationTrip? = null,
    val handoff: NavigationHandoff = NavigationHandoff.NONE,
    val lastTaskEnd: TaskEnd? = null,
)

/** Navigation adapter for the generic multi-turn task coordinator. */
class NavigationSession(
    private val scope: CoroutineScope? = null,
    private val interactionIdProvider: (String) -> String = { turnId -> "interaction:$turnId" },
    private val selectionTtlMs: Long = DEFAULT_SELECTION_TTL_MS,
    private val publish: (NavigationSnapshot) -> Unit = {},
) {
    private var expiryJob: Job? = null
    private var trip: NavigationTrip? = null
    private var handoff = NavigationHandoff.NONE
    private var lastTaskEnd: TaskEnd? = null
    private val tasks = TaskDialogueCoordinator<NavigationSelectionContext>(onChanged = ::onTaskChanged)

    @Volatile var snapshot = NavigationSnapshot()
        private set

    @Synchronized fun offer(
        candidates: List<NavigationExecutor.NavigationCandidate>,
        selectionId: String? = null,
        originTurnId: String,
    ) {
        require(candidates.isNotEmpty() && originTurnId.isNotBlank())
        val task = tasks.offer(
            interactionId = interactionIdProvider(originTurnId),
            domain = DOMAIN,
            originTurnId = originTurnId,
            expectation = NAVIGATION_EXPECTATION,
            context = NavigationSelectionContext(selectionId, candidates.toList()),
        )
        armExpiry(task.identity)
    }

    /** Compatibility/manual expiry entry; version is the task revision. */
    @Synchronized fun expire(version: Long): Boolean {
        val task = tasks.waiting(DOMAIN) ?: return false
        if (task.identity.revision != version) return false
        return tasks.finishWaiting(task.identity, TaskEndReason.EXPIRED)
    }

    @Synchronized fun cancelSelection(selectionId: String? = null): Boolean {
        val task = tasks.waiting(DOMAIN) ?: return false
        if (selectionId != null && selectionId != task.context.selectionId) return false
        return tasks.finishWaiting(task.identity, TaskEndReason.CANCELLED)
    }

    /** Atomically reserves a displayed candidate for either a voice or UI selection. */
    @Synchronized fun claimSelection(
        selectionId: String,
        candidateId: String,
    ): NavigationExecutor.NavigationCandidate? {
        val task = tasks.waiting(DOMAIN) ?: return null
        var selected: NavigationExecutor.NavigationCandidate? = null
        val claimed = tasks.claim(
            identity = task.identity,
            accepts = { context ->
                selected = context.candidates.singleOrNull { it.candidateId == candidateId }
                context.selectionId == selectionId && selected != null
            },
            update = { it.copy(selectedCandidateId = candidateId) },
        ) ?: return null
        return selected.takeIf { claimed.status == TaskStatus.EXECUTING }
    }

    @Synchronized fun abortSelection(reason: TaskEndReason = TaskEndReason.REPLACED): Boolean =
        tasks.abortActive(reason)

    @Synchronized fun beginHandoff(value: NavigationTrip) {
        trip = value.copy(waypoints = value.waypoints.toList())
        handoff = NavigationHandoff.OPENING
        publishSnapshot(tasks.active, null)
    }

    @Synchronized fun finishHandoff(accepted: Boolean) {
        handoff = if (accepted) NavigationHandoff.ACCEPTED else NavigationHandoff.FAILED
        val task = tasks.active
        if (task?.status == TaskStatus.EXECUTING) {
            tasks.finish(task.identity, if (accepted) TaskEndReason.COMPLETED else TaskEndReason.FAILED)
        } else {
            publishSnapshot(task, null)
        }
    }

    fun activeExpectation(): InputExpectation? = tasks.waiting(DOMAIN)?.expectation

    fun activeIdentity(): TaskIdentity? = tasks.waiting(DOMAIN)?.identity

    private fun armExpiry(identity: TaskIdentity) {
        expiryJob?.cancel()
        expiryJob = scope?.launch {
            delay(selectionTtlMs)
            tasks.finishWaiting(identity, TaskEndReason.EXPIRED)
        }
    }

    @Synchronized private fun onTaskChanged(
        task: DialogueTask<NavigationSelectionContext>?,
        end: TaskEnd?,
    ) {
        if (end != null) lastTaskEnd = end
        if (task == null || task.status != TaskStatus.WAITING_INPUT) expiryJob?.cancel()
        publishSnapshot(task, end)
    }

    private fun publishSnapshot(task: DialogueTask<NavigationSelectionContext>?, end: TaskEnd?) {
        val waiting = task?.takeIf { it.status == TaskStatus.WAITING_INPUT }
        snapshot = NavigationSnapshot(
            candidateVersion = task?.identity?.revision ?: end?.identity?.revision
                ?: snapshot.candidateVersion,
            taskId = task?.identity?.taskId,
            interactionId = task?.identity?.interactionId,
            taskStatus = task?.status,
            selectionId = task?.context?.selectionId,
            candidates = waiting?.context?.candidates ?: emptyList(),
            expectation = waiting?.expectation,
            trip = trip,
            handoff = handoff,
            lastTaskEnd = lastTaskEnd,
        )
        publish(snapshot)
    }

    companion object {
        const val DOMAIN = "navigation"
        const val DEFAULT_SELECTION_TTL_MS = 120_000L
        val NAVIGATION_EXPECTATION = InputExpectation("navigation_candidate", 30_000L)
    }
}
