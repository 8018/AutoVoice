package com.autovoice.voicecore.arbiter

import com.autovoice.voicecore.DecisionEntry
import com.autovoice.voicecore.Intent
import com.autovoice.voicecore.NluResult
import com.autovoice.voicecore.Reply
import java.util.LinkedHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed class RaceWinner {
    data class Cloud(val reply: Reply) : RaceWinner()

    data class Local(val nlu: NluResult) : RaceWinner() {
        val intent: Intent get() = nlu.intent
        val recognizedText: String? get() = nlu.recognizedText
    }

}

sealed class OnDeviceArbiterEvent {
    data class Received(val route: String) : OnDeviceArbiterEvent()
    data class Won(val route: String, val reason: String) : OnDeviceArbiterEvent()
    data class Lost(val route: String, val reason: String) : OnDeviceArbiterEvent()
    data class Pending(val route: String) : OnDeviceArbiterEvent()
}

/** Output from the long-lived arbitration pipeline. Rejections do not close a turn. */
sealed interface ArbitrationOutput {
    data class Winner(val value: RaceWinner) : ArbitrationOutput
    data object UnknownLocal : ArbitrationOutput
    data object AlreadyOutput : ArbitrationOutput
}

/** Why a local semantic is allowed to enter the ready FIFO. */
enum class LocalAdmission {
    /** Normal local semantics wait outside the queue until the cloud preference window opens. */
    WAIT_FOR_CLOUD,

    /** There is no usable cloud route; enqueue immediately with the supplied decision reason. */
    IMMEDIATE,
}

/**
 * Long-lived, Handler-style on-device arbitration pipeline.
 *
 * Producers only submit messages. A single consumer serializes admission, FIFO dispatch, the
 * per-turn single-output ledger and event ordering. The arbiter deliberately does not know which
 * turn is current and has no per-call/round lifecycle. Dialogue validity is checked downstream.
 *
 * The ready queue contains only eligible semantics: cloud and local hard-rule commands enter
 * immediately; ordinary local semantics are held before the queue until the cloud window opens;
 * pending moves that release time without changing already queued messages.
 */
class OnDeviceRaceArbiter(
    private val cloudWaitMs: Long = 2_000,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sink: DecisionSink,
    private val onEvent: (OnDeviceArbiterEvent) -> Unit = {},
    private val pendingWaitMs: Long = 50_000,
    private val emissionLedger: SemanticEmissionLedger = SemanticEmissionLedger(),
    retainedTurns: Int = 64,
    private val onPipelineFailure: (Throwable) -> Unit = {},
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val inbox = Channel<Message>(INBOX_CAPACITY)
    private val states = LinkedHashMap<String, TurnState>()
    private val retainedTurns = retainedTurns.also { require(it > 0) }
    private val actor: Job = scope.launch { consume() }

    private sealed interface Message {
        data class Open(val turnId: String, val output: (ArbitrationOutput) -> Unit) : Message
        data class Cloud(val turnId: String, val reply: Reply) : Message
        data class Local(
            val turnId: String,
            val nlu: NluResult,
            val admission: LocalAdmission,
            val immediateReason: String,
        ) : Message

        data class Pending(val turnId: String) : Message
        data class CloudUnavailable(val turnId: String, val reason: String) : Message
        data class ReleaseLocal(val turnId: String, val generation: Long) : Message
    }

    private enum class Route(val wire: String) { CLOUD("cloud"), LOCAL("local") }

    private sealed interface ReadyCandidate {
        val turnId: String
        val route: Route

        data class CloudCandidate(override val turnId: String, val reply: Reply) : ReadyCandidate {
            override val route = Route.CLOUD
        }

        data class LocalCandidate(
            override val turnId: String,
            val nlu: NluResult,
            val directLocal: Boolean,
            val decisionReason: String,
        ) : ReadyCandidate {
            override val route = Route.LOCAL
        }
    }

    private data class HeldLocal(val nlu: NluResult)

    private data class TurnState(
        var cloudDeadlineMs: Long,
        var generation: Long = 0,
        var heldLocal: HeldLocal? = null,
        var output: (ArbitrationOutput) -> Unit = {},
        var winner: Route? = null,
        var immediateLocalReason: String? = null,
    )

    /** Registers the result listener and starts the cloud-preference window for a turn. */
    fun openTurn(turnId: String, output: (ArbitrationOutput) -> Unit) {
        inbox.trySend(Message.Open(turnId, output)).getOrThrow()
    }

    fun submitCloud(turnId: String, reply: Reply) {
        inbox.trySend(Message.Cloud(turnId, reply)).getOrThrow()
    }

    fun submitLocal(
        turnId: String,
        nlu: NluResult,
        admission: LocalAdmission = LocalAdmission.WAIT_FOR_CLOUD,
        immediateReason: String = "cloud_unreachable",
    ) {
        inbox.trySend(Message.Local(turnId, nlu, admission, immediateReason)).getOrThrow()
    }

    fun submitPending(turnId: String) {
        inbox.trySend(Message.Pending(turnId)).getOrThrow()
    }

    /** Releases a held/future local semantic immediately because the cloud route cannot answer. */
    fun submitCloudUnavailable(turnId: String, reason: String = "cloud_unreachable") {
        inbox.trySend(Message.CloudUnavailable(turnId, reason)).getOrThrow()
    }

    private suspend fun consume() {
        for (message in inbox) {
            try {
                when (message) {
                    is Message.Open -> onOpen(message)
                    is Message.Cloud -> dispatch(ReadyCandidate.CloudCandidate(message.turnId, message.reply))
                    is Message.Local -> onLocal(message)
                    is Message.Pending -> onPending(message.turnId)
                    is Message.CloudUnavailable -> onCloudUnavailable(message)
                    is Message.ReleaseLocal -> onReleaseLocal(message)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                reportFailure(failure)
            }
        }
    }

    private fun onOpen(message: Message.Open) {
        val now = monotonicMs()
        val state = states[message.turnId]
        if (state == null) {
            states[message.turnId] = TurnState(now + cloudWaitMs, output = message.output)
        } else {
            state.output = message.output
        }
        trimStates()
    }

    private fun onLocal(message: Message.Local) {
        val now = monotonicMs()
        val state = stateFor(message.turnId, now)
        val immediateReason = state.immediateLocalReason
        val immediateLocal = message.nlu.intent.isImmediateLocalCommand()
        when {
            message.admission == LocalAdmission.IMMEDIATE || immediateReason != null -> dispatch(
                ReadyCandidate.LocalCandidate(
                    message.turnId,
                    message.nlu,
                    directLocal = false,
                    decisionReason = immediateReason ?: message.immediateReason,
                ),
            )

            immediateLocal -> dispatch(
                ReadyCandidate.LocalCandidate(
                    message.turnId,
                    message.nlu,
                    directLocal = now < state.cloudDeadlineMs,
                    decisionReason = if (now < state.cloudDeadlineMs) {
                        "local_command_won"
                    } else {
                        "cloud_timeout_use_local"
                    },
                ),
            )

            now >= state.cloudDeadlineMs -> dispatch(
                ReadyCandidate.LocalCandidate(
                    message.turnId,
                    message.nlu,
                    directLocal = false,
                    decisionReason = "cloud_timeout_use_local",
                ),
            )

            else -> {
                state.heldLocal = HeldLocal(message.nlu)
                scheduleRelease(message.turnId, state)
            }
        }
    }

    private fun onPending(turnId: String) {
        val now = monotonicMs()
        val state = stateFor(turnId, now)
        emitEvent(OnDeviceArbiterEvent.Pending(Route.CLOUD.wire))
        state.cloudDeadlineMs = now + pendingWaitMs
        state.generation += 1
        if (state.heldLocal != null) scheduleRelease(turnId, state)
    }

    private fun onCloudUnavailable(message: Message.CloudUnavailable) {
        val state = stateFor(message.turnId, monotonicMs())
        state.immediateLocalReason = message.reason
        val held = state.heldLocal ?: return
        state.heldLocal = null
        dispatch(
            ReadyCandidate.LocalCandidate(
                message.turnId,
                held.nlu,
                directLocal = false,
                decisionReason = message.reason,
            ),
        )
    }

    private fun onReleaseLocal(message: Message.ReleaseLocal) {
        val state = states[message.turnId] ?: return
        if (message.generation != state.generation) return
        val held = state.heldLocal ?: return
        val remaining = state.cloudDeadlineMs - monotonicMs()
        if (remaining > 0) {
            scheduleRelease(message.turnId, state)
            return
        }
        state.heldLocal = null
        dispatch(
            ReadyCandidate.LocalCandidate(
                message.turnId,
                held.nlu,
                directLocal = false,
                decisionReason = "cloud_timeout_use_local",
            ),
        )
    }

    /** Single actor thread: queue order itself is the atomicity boundary. */
    private fun dispatch(candidate: ReadyCandidate) {
        val state = stateFor(candidate.turnId, monotonicMs())
        emitEvent(OnDeviceArbiterEvent.Received(candidate.route.wire))

        state.winner?.let { winner ->
            emitEvent(
                OnDeviceArbiterEvent.Lost(
                    candidate.route.wire,
                    if (winner == Route.CLOUD) "cloud_already_won" else "command_already_won",
                ),
            )
            emitOutput(state, ArbitrationOutput.AlreadyOutput)
            return
        }

        if (candidate is ReadyCandidate.LocalCandidate && candidate.nlu.intent.isUnknown()) {
            emitEvent(OnDeviceArbiterEvent.Lost(Route.LOCAL.wire, "unknown_intent"))
            emitOutput(state, ArbitrationOutput.UnknownLocal)
            return
        }

        if (emissionLedger.tryEmit(candidate.turnId) != SemanticEmissionResult.ACCEPTED) {
            emitEvent(OnDeviceArbiterEvent.Lost(candidate.route.wire, "turn_already_output"))
            emitOutput(state, ArbitrationOutput.AlreadyOutput)
            return
        }

        state.winner = candidate.route
        when (candidate) {
            is ReadyCandidate.CloudCandidate -> {
                emitEvent(OnDeviceArbiterEvent.Won(Route.CLOUD.wire, "priority"))
                recordDecision(decision(candidate.turnId, Route.CLOUD.wire, "cloud_won"))
                emitOutput(state, ArbitrationOutput.Winner(RaceWinner.Cloud(candidate.reply)))
            }

            is ReadyCandidate.LocalCandidate -> {
                val eventReason = when {
                    candidate.directLocal -> "local_command"
                    candidate.decisionReason == "cloud_timeout_use_local" -> "cloud_timeout"
                    else -> candidate.decisionReason
                }
                emitEvent(OnDeviceArbiterEvent.Won(Route.LOCAL.wire, eventReason))
                recordDecision(decision(candidate.turnId, Route.LOCAL.wire, candidate.decisionReason))
                emitOutput(state, ArbitrationOutput.Winner(RaceWinner.Local(candidate.nlu)))
            }
        }
    }

    private fun scheduleRelease(turnId: String, state: TurnState) {
        val generation = ++state.generation
        val waitMs = (state.cloudDeadlineMs - monotonicMs()).coerceAtLeast(0)
        scope.launch {
            delay(waitMs)
            inbox.send(Message.ReleaseLocal(turnId, generation))
        }
    }

    private fun stateFor(turnId: String, now: Long): TurnState {
        states[turnId]?.let { return it }
        val created = TurnState(cloudDeadlineMs = now + cloudWaitMs)
        states[turnId] = created
        trimStates()
        return created
    }

    private fun trimStates() {
        while (states.size > retainedTurns) states.remove(states.keys.first())
    }

    private fun monotonicMs(): Long = System.nanoTime() / 1_000_000L

    private fun emitEvent(event: OnDeviceArbiterEvent) = isolate { onEvent(event) }

    private fun recordDecision(entry: DecisionEntry) = isolate { sink.onDecision(entry) }

    private fun emitOutput(state: TurnState, output: ArbitrationOutput) = isolate { state.output(output) }

    private inline fun isolate(block: () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            reportFailure(failure)
        }
    }

    private fun reportFailure(failure: Throwable) {
        runCatching { onPipelineFailure(failure) }
    }

    private fun decision(turnId: String, route: String, reason: String): DecisionEntry =
        DecisionEntry("on-device", route, reason, turnId, clock())

    override fun close() {
        inbox.close()
        actor.cancel()
        scope.cancel()
    }

    private companion object {
        /** Control messages are low-volume; overflow is explicit at the producer via getOrThrow. */
        const val INBOX_CAPACITY = 256
    }
}
