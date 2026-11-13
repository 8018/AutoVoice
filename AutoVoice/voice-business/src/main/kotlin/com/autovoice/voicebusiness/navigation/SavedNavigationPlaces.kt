package com.autovoice.voicebusiness.navigation

class SavedPlaceEdit internal constructor(
    val kind: SavedPlaceKind,
    internal val previous: SavedNavigationPlace?,
    internal val next: SavedNavigationPlace?,
) {
    enum class Action { SAVE, REPLACE, DELETE }

    val action: Action get() = when {
        next == null -> Action.DELETE
        previous == null -> Action.SAVE
        else -> Action.REPLACE
    }
    val previousAddress: String? get() = previous?.target?.name
    val newAddress: String? get() = next?.target?.name

    override fun toString() = "SavedPlaceEdit(kind=$kind, action=$action)"
}

sealed interface SavedPlaceProposal {
    data class Ready(val edit: SavedPlaceEdit) : SavedPlaceProposal
    data object UNKNOWN_LOCATION : SavedPlaceProposal
    data object UNSET : SavedPlaceProposal
    data object STORAGE_UNAVAILABLE : SavedPlaceProposal
}

enum class SavedPlaceCommit { SAVED, DELETED, EXPIRED, STORAGE_UNAVAILABLE }

/** Keeps confirmations bound to the profile and value originally shown to the user. */
class SavedNavigationPlaces(
    private val store: SavedNavigationPlaceStore,
    private val currentUserId: () -> String,
) {
    private var pending: SavedPlaceEdit? = null
    private var pendingUserId: String? = null

    @Synchronized
    fun read(kind: SavedPlaceKind): SavedNavigationPlace? {
        val userId = currentUserId()
        clearIfUserChanged(userId)
        return store.read(userId, kind)
    }

    fun navigate(kind: SavedPlaceKind, turnId: String, navigation: AppDialogueManager): String {
        val place = try {
            read(kind)
        } catch (_: SavedNavigationPlaceStore.StorageUnavailable) {
            navigation.dismissSelection()
            return "暂时无法读取${kind.label}，请稍后重试"
        }
        if (place == null) {
            navigation.dismissSelection()
            return "还没有设置${kind.label}，请打开设置中的常用地址"
        }
        // Only the selected point crosses the map boundary. Keep the private address out of handoff status.
        return navigation.navigateToSavedPlace(
            place.target.copy(name = kind.label), turnId, place.isGpsCoordinate,
        ).userFeedback.orEmpty()
    }

    @Synchronized
    fun prepareSave(kind: SavedPlaceKind, place: SavedNavigationPlace?): SavedPlaceProposal {
        pending = null
        if (place == null) return SavedPlaceProposal.UNKNOWN_LOCATION
        return prepare(kind, place)
    }

    @Synchronized
    fun prepareDelete(kind: SavedPlaceKind): SavedPlaceProposal = prepare(kind, null)

    @Synchronized
    fun confirm(edit: SavedPlaceEdit): SavedPlaceCommit {
        val userId = currentUserId()
        clearIfUserChanged(userId)
        if (pending !== edit) return SavedPlaceCommit.EXPIRED
        pending = null
        return try {
            if (!store.replaceIfCurrent(userId, edit.kind, edit.previous, edit.next)) SavedPlaceCommit.EXPIRED
            else if (edit.next == null) SavedPlaceCommit.DELETED else SavedPlaceCommit.SAVED
        } catch (_: SavedNavigationPlaceStore.StorageUnavailable) {
            SavedPlaceCommit.STORAGE_UNAVAILABLE
        }
    }

    @Synchronized
    fun cancel(edit: SavedPlaceEdit) {
        if (pending === edit) pending = null
    }

    private fun prepare(kind: SavedPlaceKind, place: SavedNavigationPlace?): SavedPlaceProposal {
        pending = null
        val userId = currentUserId()
        return try {
            val previous = store.read(userId, kind)
            if (previous == null && place == null) return SavedPlaceProposal.UNSET
            val edit = SavedPlaceEdit(kind, previous, place)
            pending = edit
            pendingUserId = userId
            SavedPlaceProposal.Ready(edit)
        } catch (_: SavedNavigationPlaceStore.StorageUnavailable) {
            SavedPlaceProposal.STORAGE_UNAVAILABLE
        }
    }

    private fun clearIfUserChanged(userId: String) {
        if (pendingUserId != userId) pending = null
    }
}

sealed interface SavedPlaceCommand {
    data class Navigate(val kind: SavedPlaceKind) : SavedPlaceCommand
    data class UseCurrentLocation(val kind: SavedPlaceKind) : SavedPlaceCommand
    data class Delete(val kind: SavedPlaceKind) : SavedPlaceCommand

    companion object {
        private val boundaryPunctuation = setOf('。', '！', '？', '!', '?', '.', '，', ',')
        private val home = Regex("(?:带我|送我)?回家|(?:导航|开车)(?:回家|到家|去我家)|去我家")
        private val work = Regex("(?:带我|送我|导航|开车)?去(?:我?的)?公司|(?:导航)?到公司|去上班")
        private val setHome = Regex("把(?:这里|当前位置)(?:设|设置|保存)(?:为|成)(?:我的)?(?:家|家庭地址)|家(?:搬到|换到)(?:这里|当前位置)了?")
        private val setWork = Regex("把(?:这里|当前位置)(?:设|设置|保存)(?:为|成)(?:我的)?(?:公司|公司地址)|公司(?:搬到|换到)(?:这里|当前位置)了?")
        private val deleteHome = Regex("(?:删除|清除)(?:我的)?家庭地址")
        private val deleteWork = Regex("(?:删除|清除)(?:我的)?公司地址")

        fun parse(text: String): SavedPlaceCommand? {
            val command = text.filterNot(Char::isWhitespace).trim { it in boundaryPunctuation }.removePrefix("请")
            return when {
                home.matches(command) -> Navigate(SavedPlaceKind.HOME)
                work.matches(command) -> Navigate(SavedPlaceKind.WORK)
                setHome.matches(command) -> UseCurrentLocation(SavedPlaceKind.HOME)
                setWork.matches(command) -> UseCurrentLocation(SavedPlaceKind.WORK)
                deleteHome.matches(command) -> Delete(SavedPlaceKind.HOME)
                deleteWork.matches(command) -> Delete(SavedPlaceKind.WORK)
                else -> null
            }
        }
    }
}
