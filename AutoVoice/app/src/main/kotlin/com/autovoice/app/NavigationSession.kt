package com.autovoice.app

import com.autovoice.voicecore.dialog.DialogueTask
import com.autovoice.voicecore.dialog.InputExpectation
import com.autovoice.voicecore.dialog.TaskDialogueCoordinator
import com.autovoice.voicecore.dialog.TaskEnd
import com.autovoice.voicecore.dialog.TaskEndReason
import com.autovoice.voicecore.dialog.TaskIdentity
import com.autovoice.voicecore.dialog.TaskStatus

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
    val requiresTaskIdentity: Boolean = false,
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
    val requiresTaskIdentity: Boolean = false,
)

/** Navigation adapter for the generic multi-turn task coordinator. */
class NavigationSession(
    private val interactionIdProvider: (String) -> String = { turnId -> "interaction:$turnId" },
    private val publish: (NavigationSnapshot) -> Unit = {},
) {
    private var trip: NavigationTrip? = null
    private var handoff = NavigationHandoff.NONE
    private var handoffGeneration = 0L
    private var handoffTask: TaskIdentity? = null
    private var lastTaskEnd: TaskEnd? = null
    private val tasks = TaskDialogueCoordinator<NavigationSelectionContext>(onChanged = ::onTaskChanged)

    @Volatile var snapshot = NavigationSnapshot()
        private set

    @Synchronized fun offer(
        candidates: List<NavigationExecutor.NavigationCandidate>,
        selectionId: String? = null,
        originTurnId: String,
        requiresTaskIdentity: Boolean = false,
    ) {
        require(candidates.isNotEmpty() && originTurnId.isNotBlank())
        handoffGeneration++
        tasks.offer(
            interactionId = interactionIdProvider(originTurnId),
            domain = DOMAIN,
            originTurnId = originTurnId,
            expectation = NAVIGATION_EXPECTATION,
            context = NavigationSelectionContext(selectionId, candidates.toList(), requiresTaskIdentity = requiresTaskIdentity),
        )
    }

    /** Compatibility/manual expiry entry; version is the task revision. */
    @Synchronized fun expire(version: Long): Boolean {
        val task = tasks.waiting(DOMAIN) ?: return false
        if (task.identity.revision != version) return false
        return tasks.finishWaiting(task.identity, TaskEndReason.EXPIRED)
    }

    @Synchronized fun cancelSelection(selectionId: String? = null, expected: TaskIdentity? = null): Boolean {
        val task = tasks.waiting(DOMAIN) ?: return false
        if (expected != null && expected != task.identity) return false
        if (selectionId != null && selectionId != task.context.selectionId) return false
        return tasks.finishWaiting(task.identity, TaskEndReason.CANCELLED)
    }

    /** Atomically reserves a displayed candidate for either a voice or UI selection. */
    @Synchronized fun claimSelection(
        selectionId: String,
        candidateId: String,
        expected: TaskIdentity? = null,
    ): NavigationExecutor.NavigationCandidate? {
        val task = tasks.waiting(DOMAIN) ?: return null
        if (expected != null && expected != task.identity) return null
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

    @Synchronized fun abortSelection(
        reason: TaskEndReason = TaskEndReason.REPLACED, expected: TaskIdentity? = null,
    ): Boolean = tasks.waiting(DOMAIN)?.takeIf { expected == null || expected == it.identity }
        ?.let { tasks.finishWaiting(it.identity, reason) } ?: false

    /** Only the exact failed context is closed; late protocol errors cannot close a new list. */
    @Synchronized internal fun contextMissing(ref: NavigationTaskContextRef): Boolean {
        val task = tasks.waiting(DOMAIN) ?: return false
        if (task.identity != TaskIdentity(ref.interactionId, ref.taskId, ref.revision) ||
            task.context.selectionId != ref.selectionId) return false
        return tasks.finishWaiting(task.identity, TaskEndReason.ABORTED)
    }

    /** Interaction validity belongs to DM, never to arbitration or navigation execution. */
    @Synchronized fun onDialogueState(state: com.autovoice.voicecore.dialog.DialogueSnapshot) {
        val task = tasks.waiting(DOMAIN) ?: return
        if (state.state == com.autovoice.voicecore.dialog.DialogueState.DORMANT ||
            task.identity.interactionId != state.interactionId) {
            tasks.finishWaiting(task.identity, TaskEndReason.ABORTED)
        }
    }

    @Synchronized fun beginHandoff(value: NavigationTrip): Long {
        handoffTask = tasks.active?.takeIf { it.status == TaskStatus.EXECUTING }?.identity
        val generation = ++handoffGeneration
        trip = value.copy(waypoints = value.waypoints.toList())
        handoff = NavigationHandoff.OPENING
        publishSnapshot(tasks.active, null)
        return generation
    }

    @Synchronized fun finishHandoff(generation: Long, accepted: Boolean) {
        if (generation != handoffGeneration) return
        handoff = if (accepted) NavigationHandoff.ACCEPTED else NavigationHandoff.FAILED
        val task = tasks.active
        if (task?.status == TaskStatus.EXECUTING && task.identity == handoffTask) {
            tasks.finish(task.identity, if (accepted) TaskEndReason.COMPLETED else TaskEndReason.FAILED)
        } else {
            publishSnapshot(task, null)
        }
    }

    fun activeExpectation(): InputExpectation? = tasks.waiting(DOMAIN)?.expectation

    fun activeIdentity(): TaskIdentity? = tasks.waiting(DOMAIN)?.identity

    @Synchronized private fun onTaskChanged(
        task: DialogueTask<NavigationSelectionContext>?,
        end: TaskEnd?,
    ) {
        if (end != null) lastTaskEnd = end
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
            requiresTaskIdentity = task?.context?.requiresTaskIdentity ?: false,
        )
        publish(snapshot)
    }

    companion object {
        const val DOMAIN = "navigation"
        val NAVIGATION_EXPECTATION = InputExpectation("navigation_candidate", 30_000L)
    }
}
