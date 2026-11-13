package com.autovoice.voicebusiness.navigation

/** Operations this navigation adapter can perform. Actual guidance controls require map callbacks. */
class NavigationCapabilities internal constructor(operations: Set<String>) {
    private val supported = operations.toSet()

    fun supports(operation: String): Boolean = operation in supported

    companion object {
        internal val URI_HANDOFF = NavigationCapabilities(setOf(
            NavigationExecutor.INTENT_NAVIGATE,
            NavigationExecutor.INTENT_DRAFT_TRIP,
            NavigationExecutor.INTENT_CHOOSE_DESTINATION,
            NavigationExecutor.INTENT_CANCEL_NAVIGATION,
        ))
    }
}
