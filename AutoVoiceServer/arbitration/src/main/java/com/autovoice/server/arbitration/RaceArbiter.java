package com.autovoice.server.arbitration;

import com.autovoice.server.contracts.ArbiterDecision;
import com.autovoice.server.contracts.CloudArbiterEvent;
import com.autovoice.server.contracts.DecisionEntry;
import com.autovoice.server.contracts.Intent;
import com.autovoice.server.contracts.LlmProvider;
import com.autovoice.server.contracts.OfflineCommandHit;
import com.autovoice.server.contracts.Reply;
import com.autovoice.server.contracts.SessionContext;
import com.autovoice.server.contracts.SpeakTexts;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * Process-lived cloud arbitration pipeline.
 *
 * <p>Future callbacks and timers only post messages. A serial FIFO handler performs admission and
 * dispatch, so winner selection never runs concurrently and needs no per-turn CAS. The pipeline
 * does not know which turn is current; the gateway output permit remains the downstream authority.
 * Cloud-specific policy is intentionally different from the device policy:</p>
 * <ul>
 *   <li>an eligible offline air-condition command enters the ready queue immediately;</li>
 *   <li>an LLM reply is held while offline recognition is unresolved, then released by an offline
 *       miss or the finite offline grace timer;</li>
 *   <li>a safety timer posts a synthetic fallback candidate to bound server resources.</li>
 * </ul>
 */
public final class RaceArbiter {
    static final long DEFAULT_OFFLINE_GRACE_MS = 1500;

    private static final String SAFETY_TEXT = "网络开小差了，请稍后再试";
    private static final String ARBITER_CLOUD = "cloud";
    private static final String ROUTE_LLM = "llm";
    private static final String ROUTE_NLU_TRADITIONAL = "nlu-traditional";

    private final long safetyTimeoutMs;
    private final long offlineGraceMs;
    private final ScheduledExecutorService scheduler;
    private final DecisionSink sink;
    private final BiConsumer<String, CloudArbiterEvent> eventSink;

    /** Handler-style serial executor backed by the existing shared scheduler. */
    private final ConcurrentLinkedQueue<Runnable> messages = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean draining = new AtomicBoolean(false);

    public RaceArbiter(long safetyTimeoutMs, ScheduledExecutorService scheduler, DecisionSink sink) {
        this(safetyTimeoutMs, DEFAULT_OFFLINE_GRACE_MS, scheduler, sink, (uid, event) -> {});
    }

    public RaceArbiter(long safetyTimeoutMs, long offlineGraceMs,
                       ScheduledExecutorService scheduler, DecisionSink sink) {
        this(safetyTimeoutMs, offlineGraceMs, scheduler, sink, (uid, event) -> {});
    }

    public RaceArbiter(long safetyTimeoutMs, long offlineGraceMs,
                       ScheduledExecutorService scheduler, DecisionSink sink,
                       BiConsumer<String, CloudArbiterEvent> eventSink) {
        this.safetyTimeoutMs = safetyTimeoutMs;
        this.offlineGraceMs = offlineGraceMs;
        this.scheduler = scheduler;
        this.sink = sink;
        this.eventSink = eventSink;
    }

    public CompletableFuture<ArbiterDecision> decide(CompletableFuture<OfflineCommandHit> offline,
                                                     CompletableFuture<Reply> llm,
                                                     SessionContext ctx, String utteranceId) {
        return decide(offline, llm, ctx, utteranceId, null);
    }

    public CompletableFuture<ArbiterDecision> decide(CompletableFuture<OfflineCommandHit> offline,
                                                     CompletableFuture<Reply> llm,
                                                     SessionContext ctx, String utteranceId,
                                                     String segmentId) {
        Turn turn = new Turn(utteranceId, segmentId, offline, llm);

        // Registration order is explicit. Already-completed futures therefore enter FIFO in this
        // order; otherwise actual callback submission order decides, exactly like a Handler queue.
        offline.whenComplete((hit, error) -> post(() -> onOffline(turn, hit, error)));
        llm.whenComplete((reply, error) -> post(() -> onLlm(turn, reply, error)));
        scheduler.schedule(() -> post(() -> onSafety(turn)), safetyTimeoutMs, TimeUnit.MILLISECONDS);
        return turn.output;
    }

    private void onOffline(Turn turn, OfflineCommandHit hit, Throwable error) {
        turn.offlineResolved = true;
        if (error != null || hit == null || !isAirConControl(hit.intent())) {
            if (!turn.llmFuture.isDone()) {
                onEvent(turn.utteranceId, CloudArbiterEvent.pending(ROUTE_LLM, turn.segmentId));
            }
            if (turn.heldLlm != null) {
                Reply ready = turn.heldLlm;
                turn.heldLlm = null;
                dispatchLlm(turn, ready);
            }
            return;
        }
        dispatchOffline(turn, hit);
    }

    private void onLlm(Turn turn, Reply reply, Throwable error) {
        if (error != null || reply == null) return;
        onEvent(turn.utteranceId, CloudArbiterEvent.received(ROUTE_LLM));
        if (turn.offlineResolved) {
            dispatchLlm(turn, reply);
            return;
        }
        turn.heldLlm = reply;
        long generation = ++turn.graceGeneration;
        scheduler.schedule(
                () -> post(() -> onGraceExpired(turn, generation)),
                offlineGraceMs,
                TimeUnit.MILLISECONDS);
    }

    private void onGraceExpired(Turn turn, long generation) {
        if (generation != turn.graceGeneration || turn.heldLlm == null) return;
        Reply ready = turn.heldLlm;
        turn.heldLlm = null;
        dispatchLlm(turn, ready);
    }

    private void dispatchOffline(Turn turn, OfflineCommandHit hit) {
        onEvent(turn.utteranceId, CloudArbiterEvent.received(ROUTE_NLU_TRADITIONAL));
        if (turn.winner != null) {
            if (Winner.LLM == turn.winner) {
                onEvent(turn.utteranceId, CloudArbiterEvent.lost(
                        ROUTE_NLU_TRADITIONAL, CloudArbiterEvent.Reason.LLM_ALREADY_WON));
            }
            return;
        }
        turn.winner = Winner.OFFLINE;
        Reply reply = Reply.ofAction(hit.intent(), SpeakTexts.speak(hit.intent()));
        sink.log(entry(turn.utteranceId, ROUTE_NLU_TRADITIONAL, "offline_won"));
        onEvent(turn.utteranceId, CloudArbiterEvent.won(
                ROUTE_NLU_TRADITIONAL, CloudArbiterEvent.Reason.PRIORITY, "offline_won"));
        turn.output.complete(new ArbiterDecision(reply, "offline_won", hit.text()));
    }

    private void dispatchLlm(Turn turn, Reply reply) {
        if (turn.winner != null) {
            if (Winner.OFFLINE == turn.winner) {
                onEvent(turn.utteranceId, CloudArbiterEvent.lost(
                        ROUTE_LLM, CloudArbiterEvent.Reason.COMMAND_ALREADY_WON));
            }
            return;
        }
        turn.winner = Winner.LLM;
        sink.log(entry(turn.utteranceId, ROUTE_LLM, "llm_reply"));
        onEvent(turn.utteranceId, CloudArbiterEvent.won(
                ROUTE_LLM, CloudArbiterEvent.Reason.PRIORITY, "llm_reply"));
        turn.output.complete(new ArbiterDecision(reply, "llm_reply", null));
    }

    private void onSafety(Turn turn) {
        if (turn.winner != null) return;
        turn.winner = Winner.SAFETY;
        sink.log(entry(turn.utteranceId, ROUTE_LLM, "safety_timeout"));
        onEvent(turn.utteranceId, CloudArbiterEvent.won(
                ROUTE_LLM, CloudArbiterEvent.Reason.LLM_TIMEOUT, "safety_timeout"));
        turn.output.complete(new ArbiterDecision(
                Reply.ofText(SAFETY_TEXT), "safety_timeout", null));
    }

    private void post(Runnable message) {
        messages.add(message);
        if (draining.compareAndSet(false, true)) scheduler.execute(this::drainMessages);
    }

    private void drainMessages() {
        do {
            Runnable message;
            while ((message = messages.poll()) != null) message.run();
            draining.set(false);
            // A producer may enqueue between poll()==null and draining=false.
        } while (!messages.isEmpty() && draining.compareAndSet(false, true));
    }

    private void onEvent(String utteranceId, CloudArbiterEvent event) {
        eventSink.accept(utteranceId, event);
    }

    /** Legacy single-route entry uses the same message pipeline. */
    public CompletableFuture<Reply> decide(String text, LlmProvider llm,
                                           SessionContext ctx, String utteranceId) {
        CompletableFuture<OfflineCommandHit> offline = CompletableFuture.completedFuture(null);
        return decide(offline, llm.chat(text, ctx, utteranceId), ctx, utteranceId)
                .thenApply(ArbiterDecision::reply);
    }

    private static boolean isAirConControl(Intent intent) {
        if (!"climate".equals(intent.domain())) return false;
        return "power_on".equals(intent.intent())
                || "power_off".equals(intent.intent())
                || "set_temperature".equals(intent.intent());
    }

    private static DecisionEntry entry(String utteranceId, String route, String reason) {
        return new DecisionEntry(ARBITER_CLOUD, route, reason,
                utteranceId, System.currentTimeMillis());
    }

    private enum Winner { OFFLINE, LLM, SAFETY }

    private static final class Turn {
        final String utteranceId;
        final String segmentId;
        final CompletableFuture<OfflineCommandHit> offlineFuture;
        final CompletableFuture<Reply> llmFuture;
        final CompletableFuture<ArbiterDecision> output = new CompletableFuture<>();
        boolean offlineResolved;
        Reply heldLlm;
        long graceGeneration;
        Winner winner;

        Turn(String utteranceId, String segmentId,
             CompletableFuture<OfflineCommandHit> offlineFuture,
             CompletableFuture<Reply> llmFuture) {
            this.utteranceId = utteranceId;
            this.segmentId = segmentId;
            this.offlineFuture = offlineFuture;
            this.llmFuture = llmFuture;
        }
    }
}
