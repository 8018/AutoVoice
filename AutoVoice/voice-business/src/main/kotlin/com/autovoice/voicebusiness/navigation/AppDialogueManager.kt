package com.autovoice.voicebusiness.navigation

import com.autovoice.voicecore.Intent
import com.autovoice.voicebusiness.navigation.NavigationExecutor.Companion.PREPARING_MULTISTOP
import com.autovoice.voicecore.SlotValue
import com.autovoice.voicebusiness.dialog.DialogueSnapshot
import com.autovoice.voicebusiness.dialog.TaskEndReason

/** Application-level task coordination. Recognition, arbitration and transport remain outside. */
class AppDialogueManager(
    interactionIdProvider: (String) -> String,
    private val onNavigation: (NavigationSnapshot) -> Unit,
    launchNavigation: (String) -> Boolean,
    savedPlace: (SavedPlaceKind) -> SavedNavigationPlace? = { null },
    mapRequest: ((NavigationTrip, String, Boolean, RoutePreference?) -> NavigationActionResult?)? = null,
    normalizeTrip: ((NavigationNormalizationRequest) -> Unit)? = null,
    private val applyChatMode: (Boolean) -> Boolean = { true },
) {
    private var sendContext: (NavigationTaskContextRef) -> Unit = {}
    private var publishedContext: NavigationTaskContextRef? = null
    /** Business-mode ownership; capture and transport keep their own physical readiness state. */
    @Volatile private var chatMode = false

    val isChatMode: Boolean get() = chatMode

    /** Enter/exit the chat domain once; do not publish a mode the capture layer rejected. */
    @Synchronized
    fun setChatMode(enabled: Boolean): Boolean {
        if (chatMode == enabled) return false
        if (!applyChatMode(enabled)) return false
        chatMode = enabled
        return true
    }

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
    val navigationExecutor = NavigationExecutor(
        session = navigationSession, savedPlace = savedPlace, mapRequest = mapRequest,
        normalizeTrip = normalizeTrip, opener = launchNavigation,
    )

    fun bindNavigationContextSender(sender: (NavigationTaskContextRef) -> Unit) {
        sendContext = sender
        publishedContext?.let(sender)
    }

    fun onDialogueState(snapshot: DialogueSnapshot) = navigationSession.onDialogueState(snapshot)

    fun dismissSelection() = navigationSession.cancelSelection()

    fun abortPendingTask() = navigationExecutor.abortPendingTask()

    fun normalizingDraftRevision(): Long? = navigationSession.snapshot
        .takeIf { it.normalizingDraft }?.draft?.revision

    fun replaceNormalizingDraftUnless(isLocalCommand: () -> Boolean) {
        val revision = normalizingDraftRevision() ?: return
        if (!isLocalCommand()) navigationSession.dismissDraft(revision)
    }

    fun close() {
        setChatMode(false)
        navigationSession.abortSelection(TaskEndReason.ABORTED)
    }

    /** UI selection uses the same task identity and atomic claim path as voice selection. */
    fun selectCandidate(
        candidate: NavigationExecutor.NavigationCandidate,
        turnId: String,
    ): NavigationActionResult? {
        val snapshot = navigationSession.snapshot
        val selectionId = snapshot.selectionId ?: return null
        val target = runCatching { NavigationTarget(candidate.poiname, candidate.lat, candidate.lon) }.getOrNull()
            ?: return null
        return navigate(
            target = target,
            turnId = turnId,
            operation = "select",
            slots = identitySlots(snapshot) + mapOf(
                "selectionId" to SlotValue.StringValue(selectionId),
                "candidateId" to SlotValue.StringValue(candidate.candidateId),
            ),
            source = "ui.navigation_candidate",
            preference = snapshot.routePreviewPreference,
        )
    }

    fun selectDraftStop(revision: Long, candidateId: String): String? =
        navigationSession.selectDraftStop(revision, candidateId)?.prompt()

    fun confirmDraft(revision: Long): String? = navigationExecutor.confirmDraft(revision)?.userFeedback

    fun dismissDraft(revision: Long): String? =
        "已放弃行程草稿".takeIf { navigationSession.dismissDraft(revision) }

    /** Resolves a local follow-up against the version visible when the input was adopted. */
    fun draftCommand(text: String): (() -> String?)? {
        val snapshot = navigationSession.snapshot
        val draft = snapshot.draft ?: return null
        val normalized = text.filterNot(Char::isWhitespace).trim { it in "。！？!?.，," }
        if (normalized.isBlank()) return null
        val revision = draft.revision
        return when (normalized) {
            "确认", "确认行程", "确认路线", "就走这条路线" -> {
                {
                    val current = navigationSession.snapshot
                    if (current.draft?.revision == revision && current.normalizingDraft) PREPARING_MULTISTOP
                    else confirmDraft(revision)
                }
            }
            "取消", "取消行程", "放弃行程", "放弃草稿" -> { { dismissDraft(revision) } }
            "查看行程", "查看草稿", "准备去哪几个地方", "看看准备去哪几个地方" -> {
                {
                    val current = navigationSession.snapshot
                    current.draft?.takeIf { it.revision == revision }?.let {
                        if (current.normalizingDraft) PREPARING_MULTISTOP else it.prompt()
                    }
                }
            }
            else -> {
                val ordinal = when (normalized.removePrefix("选").removePrefix("第").removeSuffix("个")) {
                    "一", "1" -> 0
                    "二", "2" -> 1
                    "三", "3" -> 2
                    else -> null
                }
                val candidate = if (ordinal != null) snapshot.candidates.getOrNull(ordinal)
                    else snapshot.candidates.singleOrNull { it.poiname == normalized || it.address == normalized }
                candidate?.let { choice -> { selectDraftStop(revision, choice.candidateId) } }
            }
        }
    }

    /** A local saved place replaces the pending search and never needs to pass through the server. */
    fun navigateToSavedPlace(
        target: NavigationTarget,
        turnId: String,
        isGpsCoordinate: Boolean = false,
    ): NavigationActionResult = navigate(
        target = target,
        turnId = turnId,
        operation = "start_new",
        slots = identitySlots(navigationSession.snapshot),
        source = "local.saved_navigation",
        isGpsCoordinate = isGpsCoordinate,
    )

    private fun navigate(
        target: NavigationTarget,
        turnId: String,
        operation: String,
        slots: Map<String, SlotValue>,
        source: String,
        isGpsCoordinate: Boolean = false,
        preference: RoutePreference? = null,
    ): NavigationActionResult = navigationExecutor.executeWithResult(
        Intent(
            schemaVersion = "1.0",
            domain = NavigationExecutor.DOMAIN_NAVIGATION,
            intent = if (preference == null) NavigationExecutor.INTENT_NAVIGATE else NavigationExecutor.INTENT_PREVIEW_ROUTES,
            slots = slots + mapOf(
                "navigationOperation" to SlotValue.StringValue(operation),
                NavigationExecutor.SLOT_POINAME to SlotValue.StringValue(target.name),
                NavigationExecutor.SLOT_LAT to SlotValue.Number(target.latitude),
                NavigationExecutor.SLOT_LON to SlotValue.Number(target.longitude),
            ) + if (preference == null) emptyMap() else mapOf("routePreviewPreference" to SlotValue.StringValue(preference.name)),
            confidence = 1.0,
            source = source,
        ),
        turnId,
        isGpsCoordinate,
    )

    private fun identitySlots(snapshot: NavigationSnapshot): Map<String, SlotValue> {
        val taskId = snapshot.taskId ?: return emptyMap()
        val interactionId = snapshot.interactionId ?: return emptyMap()
        return mapOf(
            "taskId" to SlotValue.StringValue(taskId),
            "taskRevision" to SlotValue.Number(snapshot.candidateVersion.toDouble()),
            "interactionId" to SlotValue.StringValue(interactionId),
        )
    }
}
