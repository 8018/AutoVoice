package com.autovoice.voicebusiness.navigation

import com.autovoice.voicecore.Intent

enum class NavigationActionStatus { UNSUPPORTED, PENDING, ACCEPTED, SUCCEEDED, FAILED }

enum class NavigationActionError { UNSUPPORTED, INVALID_REQUEST, STALE_TASK, MAP_OPEN_FAILED }

data class NavigationActionResult(
    val operationId: String,
    val status: NavigationActionStatus,
    val message: String,
    val error: NavigationActionError? = null,
) {
    /** A late or duplicate task result must not overwrite the current turn's feedback. */
    val userFeedback: String?
        get() = message.takeIf { it.isNotBlank() && error != NavigationActionError.STALE_TASK }

    companion object {
        fun unsupported(intent: Intent, turnId: String) = NavigationActionResult(
            operationId(intent, turnId),
            NavigationActionStatus.UNSUPPORTED,
            "当前设备不支持这个导航操作",
            NavigationActionError.UNSUPPORTED,
        )

        internal fun operationId(intent: Intent, turnId: String): String {
            val taskId = intent.slots["taskId"]?.value as? String
            val revision = (intent.slots["taskRevision"]?.value as? Number)?.toLong()
            val interactionId = intent.slots["interactionId"]?.value as? String
            val operation = intent.slots["navigationOperation"]?.value as? String
            return if (taskId != null && revision != null && interactionId != null && operation in setOf("select", "cancel")) {
                "$interactionId:$taskId:$revision:$operation"
            } else {
                "$turnId:${intent.intent}"
            }
        }
    }
}
