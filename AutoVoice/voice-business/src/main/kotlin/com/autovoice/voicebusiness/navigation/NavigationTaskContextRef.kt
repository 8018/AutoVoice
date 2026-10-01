package com.autovoice.voicebusiness.navigation

/** Immutable identity sent to the cloud while a navigation selection task is active. */
data class NavigationTaskContextRef(
    val taskId: String,
    val revision: Long,
    val interactionId: String,
    val selectionId: String,
    val active: Boolean = true,
) {
    init {
        require(taskId.isNotBlank() && revision > 0 && interactionId.isNotBlank())
        if (active) require(selectionId.isNotBlank())
    }
}
