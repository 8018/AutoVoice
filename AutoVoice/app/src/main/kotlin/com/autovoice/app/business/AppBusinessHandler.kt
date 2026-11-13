package com.autovoice.app.business

import com.autovoice.app.MockVehicleState
import com.autovoice.voicebusiness.navigation.NavigationExecutor
import com.autovoice.voicebusiness.navigation.NavigationActionError
import com.autovoice.voicebusiness.navigation.NavigationActionResult
import com.autovoice.voicebusiness.navigation.NavigationActionStatus
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
    private val onExitDialogue: () -> Unit = {},
    private val onTravelGuideStarted: (String) -> Unit = {},
) : BusinessHandler {
    override fun handle(command: BusinessCommand): BusinessResult {
        val intent = command.intent
        if (intent.domain == "conversation") {
            navigation?.abortPendingTask()
            return when (intent.intent) {
                "enter_chat" -> BusinessResult.applied().also { onConversationMode(true) }
                "exit_chat" -> BusinessResult.applied().also { onConversationMode(false) }
                "exit_dialogue" -> BusinessResult.applied().also {
                    navigation?.abortPendingTask()
                    onConversationMode(false)
                    onExitDialogue()
                }
                else -> BusinessResult.failed()
            }
        }
        if (intent.domain == "travel" && intent.intent == "plan_guide") {
            return when (actionGateway.execute(command.turnId) { true }) {
                ActionExecutionGateway.Result.APPLIED -> BusinessResult.applied().also {
                    onTravelGuideStarted(command.turnId)
                }
                ActionExecutionGateway.Result.DUPLICATE -> BusinessResult(BusinessResult.Status.DUPLICATE)
                ActionExecutionGateway.Result.INVALID_TURN -> BusinessResult.rejected()
                ActionExecutionGateway.Result.FAILED -> BusinessResult.failed()
            }
        }
        if (intent.domain == NavigationExecutor.DOMAIN_NAVIGATION) return handleNavigation(command)

        var appliedText: String? = null
        val result = actionGateway.execute(command.turnId) {
            navigation?.abortPendingTask()
            appliedText = vehicle.apply(intent)
            appliedText != null
        }
        return when (result) {
            ActionExecutionGateway.Result.APPLIED -> BusinessResult.applied(appliedText).also { onVehicleApplied() }
            ActionExecutionGateway.Result.FAILED -> BusinessResult.failed()
            ActionExecutionGateway.Result.DUPLICATE -> BusinessResult(BusinessResult.Status.DUPLICATE)
            ActionExecutionGateway.Result.INVALID_TURN -> BusinessResult.rejected()
        }
    }

    private fun handleNavigation(command: BusinessCommand): BusinessResult {
        var navigationResult: NavigationActionResult? = null
        val started = System.nanoTime()
        val gate = actionGateway.execute(command.turnId) {
            navigationResult = navigation?.executeWithResult(command.intent, command.turnId)
                ?: NavigationActionResult.unsupported(command.intent, command.turnId)
            true
        }
        return when (gate) {
            ActionExecutionGateway.Result.APPLIED -> {
                val result = checkNotNull(navigationResult)
                val status = when (result.status) {
                    NavigationActionStatus.PENDING,
                    NavigationActionStatus.ACCEPTED,
                    NavigationActionStatus.SUCCEEDED -> BusinessResult.Status.APPLIED
                    NavigationActionStatus.UNSUPPORTED -> BusinessResult.Status.FAILED
                    NavigationActionStatus.FAILED -> if (result.error == NavigationActionError.STALE_TASK) {
                        BusinessResult.Status.REJECTED
                    } else {
                        BusinessResult.Status.FAILED
                    }
                }
                BusinessResult(
                    status,
                    result.userFeedback,
                    BusinessResult.Execution(
                        result.status.name.lowercase(),
                        result.error?.name?.lowercase(),
                        (System.nanoTime() - started) / 1_000_000L,
                    ),
                )
            }
            ActionExecutionGateway.Result.FAILED -> BusinessResult.failed()
            ActionExecutionGateway.Result.DUPLICATE -> BusinessResult(BusinessResult.Status.DUPLICATE)
            ActionExecutionGateway.Result.INVALID_TURN -> BusinessResult.rejected()
        }
    }
}
