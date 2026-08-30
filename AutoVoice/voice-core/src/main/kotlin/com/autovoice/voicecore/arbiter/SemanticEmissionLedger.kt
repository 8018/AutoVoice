package com.autovoice.voicecore.arbiter

enum class SemanticEmissionResult {
    ACCEPTED,
    TURN_ALREADY_EMITTED,
}

/**
 * 仲裁流水线的按轮语义输出账本。
 *
 * 它只知道某个 turn 是否已经输出过语义，不知道也不判断哪个 turn 是状态机当前轮。
 * 当前轮校验必须在仲裁输出下游由 DialogueStateMachine 完成。
 */
class SemanticEmissionLedger(private val retainedTurns: Int = 64) {
    private val emittedTurns = LinkedHashSet<String>()

    init {
        require(retainedTurns > 0)
    }

    @Synchronized
    fun tryEmit(turnId: String): SemanticEmissionResult {
        // 未接轮次追踪的旧调用无法安全去重，保持兼容；生产链路始终提供非空 turnId。
        if (turnId.isBlank()) return SemanticEmissionResult.ACCEPTED
        if (!emittedTurns.add(turnId)) return SemanticEmissionResult.TURN_ALREADY_EMITTED
        while (emittedTurns.size > retainedTurns) {
            emittedTurns.remove(emittedTurns.first())
        }
        return SemanticEmissionResult.ACCEPTED
    }
}
