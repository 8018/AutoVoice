package com.autovoice.app.navigation

import com.autovoice.app.action.ActionExecutionGateway
import com.autovoice.voicebusiness.navigation.SavedPlaceCommand

/** Uses the accepted turn's identity to consume duplicate replies without repeating a handoff. */
internal class SavedNavigationCommandRouter(
    private val handleCommand: (String, SavedPlaceCommand) -> String,
) {
    private val gate = ActionExecutionGateway()

    fun handle(turnId: String, recognizedText: String): String? {
        val command = SavedPlaceCommand.parse(recognizedText) ?: return null
        var message = ""
        val status = gate.execute(turnId) {
            message = handleCommand(turnId, command)
            true
        }
        return when (status) {
            ActionExecutionGateway.Result.APPLIED -> message
            ActionExecutionGateway.Result.DUPLICATE -> ""
            else -> "常用地址操作未完成，请重试"
        }
    }
}
