package com.autovoice.app

import com.autovoice.voicecore.Intent
import com.autovoice.voicecore.SlotValue
import com.autovoice.voicecore.dialog.DialogueSnapshot
import com.autovoice.voicecore.dialog.TaskEndReason

/** Application-level task coordination. Recognition, arbitration and transport remain outside. */
internal class AppDialogueManager(
    interactionIdProvider: (String) -> String,
    private val onNavigation: (NavigationSnapshot) -> Unit,
    launchNavigation: (String) -> Boolean,
) {
    private var sendContext: (NavigationTaskContextRef) -> Unit = {}
    private var publishedContext: NavigationTaskContextRef? = null

    val navigationSession = NavigationSession(interactionIdProvider) { snapshot ->
        onNavigation(snapshot)
        val next = if (snapshot.taskId != null && snapshot.interactionId != null &&
            !snapshot.selectionId.isNullOrBlank()
        ) {
            NavigationTaskContextRef(snapshot.taskId, snapshot.candidateVersion,
                snapshot.interactionId, snapshot.selectionId)
        } else null
        if (next != null && next != publishedContext) {
            publishedContext = next
            sendContext(next)
        } else if (next == null) {
            publishedContext?.let { sendContext(it.copy(active = false)) }
            publishedContext = null
        }
    }
    val navigationExecutor = NavigationExecutor(session = navigationSession, opener = launchNavigation)

    fun bindNavigationContextSender(sender: (NavigationTaskContextRef) -> Unit) {
        sendContext = sender
        publishedContext?.let(sender)
    }

    fun onDialogueState(snapshot: DialogueSnapshot) = navigationSession.onDialogueState(snapshot)

    fun listeningDirective(): TaskListeningDirective? =
        navigationSession.activeIdentity()?.let { identity ->
            TaskListeningDirective(identity.revision,
                navigationSession.activeExpectation()?.listenWindowMs ?: return@let null)
        }

    fun matchesTaskRevision(revision: Long): Boolean =
        navigationSession.activeIdentity()?.revision == revision

    fun onFollowUpExpired(revision: Long?): Boolean =
        revision == null || navigationSession.expire(revision)

    fun dismissSelection() = navigationSession.cancelSelection()

    fun abortPendingTask() = navigationExecutor.abortPendingTask()

    fun close() = navigationSession.abortSelection(TaskEndReason.ABORTED)

    /** UI selection uses the same task identity and atomic claim path as voice selection. */
    fun selectCandidate(candidate: NavigationExecutor.NavigationCandidate, turnId: String) {
        val snapshot = navigationSession.snapshot
        val selectionId = snapshot.selectionId ?: return
        val intent = Intent(
            schemaVersion = "1.0",
            domain = NavigationExecutor.DOMAIN_NAVIGATION,
            intent = NavigationExecutor.INTENT_NAVIGATE,
            slots = mapOf(
                "navigationOperation" to SlotValue.StringValue("select"),
                "taskId" to SlotValue.StringValue(snapshot.taskId ?: return),
                "taskRevision" to SlotValue.Number(snapshot.candidateVersion.toDouble()),
                "interactionId" to SlotValue.StringValue(snapshot.interactionId ?: return),
                "selectionId" to SlotValue.StringValue(selectionId),
                "candidateId" to SlotValue.StringValue(candidate.candidateId),
                NavigationExecutor.SLOT_POINAME to SlotValue.StringValue(candidate.poiname),
                NavigationExecutor.SLOT_LAT to SlotValue.Number(candidate.lat),
                NavigationExecutor.SLOT_LON to SlotValue.Number(candidate.lon),
            ),
            confidence = 1.0,
            source = "ui.navigation_candidate",
        )
        navigationExecutor.execute(intent, turnId)
    }
}
