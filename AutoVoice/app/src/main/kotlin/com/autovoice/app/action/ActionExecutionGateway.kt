package com.autovoice.app.action

/**
 * Gives each current interaction one atomic action slot.
 *
 * The gate is intentionally in-memory. A reconnect or process restart never restores unfinished
 * work; saying the same words again creates a new turn and therefore a new action.
 */
class ActionExecutionGateway(private val retainedTurns: Int = 64) {
    private val claimedTurns = LinkedHashSet<String>()

    init {
        require(retainedTurns > 0)
    }

    fun execute(turnId: String, action: () -> Boolean): Result {
        if (turnId.isBlank()) return Result.INVALID_TURN
        if (!claim(turnId)) return Result.DUPLICATE
        return if (runCatching(action).getOrDefault(false)) Result.APPLIED else Result.FAILED
    }

    @Synchronized
    private fun claim(turnId: String): Boolean {
        if (!claimedTurns.add(turnId)) return false
        while (claimedTurns.size > retainedTurns) claimedTurns.remove(claimedTurns.first())
        return true
    }

    enum class Result {
        APPLIED,
        FAILED,
        DUPLICATE,
        INVALID_TURN,
    }
}
