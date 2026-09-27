package com.autovoice.app.business

import com.autovoice.app.MockVehicleState
import com.autovoice.app.NavigationExecutor
import com.autovoice.app.action.ActionExecutionGateway
import com.autovoice.business.BusinessCommand
import com.autovoice.business.BusinessHandler
import com.autovoice.business.BusinessResult

/** Application business boundary. VoiceEngine only sees [BusinessHandler]. */
class AppBusinessHandler(
    private val vehicle: MockVehicleState,
    private val navigation: NavigationExecutor?,
    private val actionGateway: ActionExecutionGateway = ActionExecutionGateway(),
    private val onVehicleApplied: () -> Unit = {},
    private val onConversationMode: (Boolean) -> Unit = {},
) : BusinessHandler {
    override fun handle(command: BusinessCommand): BusinessResult {
        val intent = command.intent
        if (intent.domain == "conversation") {
            navigation?.abortPendingTask()
            return when (intent.intent) {
                "enter_chat" -> BusinessResult.applied().also { onConversationMode(true) }
                "exit_chat" -> BusinessResult.applied().also { onConversationMode(false) }
                else -> BusinessResult.failed()
            }
        }
        var appliedText: String? = null
        val result = actionGateway.execute(command.turnId) {
            if (intent.domain == NavigationExecutor.DOMAIN_NAVIGATION) {
                navigation?.execute(intent, command.turnId) == true
            } else {
                navigation?.abortPendingTask()
                appliedText = vehicle.apply(intent)
                appliedText != null
            }
        }
        return when (result) {
            ActionExecutionGateway.Result.APPLIED -> {
                if (intent.domain != NavigationExecutor.DOMAIN_NAVIGATION) onVehicleApplied()
                BusinessResult.applied(appliedText)
            }
            ActionExecutionGateway.Result.FAILED -> BusinessResult.failed()
            ActionExecutionGateway.Result.DUPLICATE -> BusinessResult(BusinessResult.Status.DUPLICATE)
            ActionExecutionGateway.Result.INVALID_TURN -> BusinessResult.rejected()
        }
    }
}
