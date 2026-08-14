package com.autovoice.server.arbitration;

import com.autovoice.server.contracts.ArbiterDecision;
import com.autovoice.server.contracts.CloudArbiterEvent;
import com.autovoice.server.contracts.DecisionEntry;
import com.autovoice.server.contracts.LlmProvider;
import com.autovoice.server.contracts.OfflineCommandHit;
import com.autovoice.server.contracts.Reply;
import com.autovoice.server.contracts.SessionContext;
import com.autovoice.server.contracts.SpeakTexts;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * 云端仲裁器（双候选竞速）：离线命令识别 ∥ ASR→LLM，并把决策写入 {@link DecisionSink}。
 *
 * <p>收敛规则（spec §5.2 修订，离线命令词为"传统链路"）：</p>
 * <ul>
 *   <li><b>offline 命中</b>（识别到命令词且规则映射非 unknown）→ 立即胜出，不等 LLM
 *       （route = {@code nlu-traditional}，reason = {@code offline_won}）；</li>
 *   <li><b>LLM 到达</b>：若 offline 已完成（无论命中与否）→ LLM 立即胜出；否则起
 *       {@code offlineGraceMs} 宽限期等 offline——宽限期内 offline 命中 → 离线胜出，
 *       到点未命中 → LLM 胜出（reason = {@code llm_reply}）；</li>
 *   <li><b>safety 兜底</b>：{@code safetyTimeoutMs} 内无一收敛 → 兜底文本
 *       （reason = {@code safety_timeout}）；</li>
 *   <li>迟到的 offline（LLM 已胜出后完成）被 {@link AtomicBoolean} 单赢家守卫拒绝，不抢结果。</li>
 * </ul>
 *
 * <p>并发实现：单赢家 CAS——首个成功者 complete 结果并写日志；safety 与宽限期计时用
 * {@link ScheduledExecutorService} 的 schedule 任务，触发时再经 CAS 判定防双写。</p>
 *
 * <p>事件出口（B3 需求 3，仲裁行为不变只补事件）：收到候选（asr 命令词 / llm 语义）、
 * 胜出（priority 优先 / llm_timeout 超时未收到 llm）、失败（llm_already_won /
 * command_already_won；not_latest_round 枚举保留不实现）——经 {@code eventSink} 发出，
 * 装配方映射为 telemetry 插桩；不装配（缺省 no-op）时零影响。</p>
 */
public final class RaceArbiter {

    /** 离线宽限期默认值（LLM 到达后等待离线结果的窗口）。 */
    static final long DEFAULT_OFFLINE_GRACE_MS = 1500;

    private static final String SAFETY_TEXT = "网络开小差了，请稍后再试";
    private static final String ARBITER_CLOUD = "cloud";
    private static final String ROUTE_LLM = "llm";
    private static final String ROUTE_NLU_TRADITIONAL = "nlu-traditional";

    private final long safetyTimeoutMs;
    private final long offlineGraceMs;
    private final ScheduledExecutorService scheduler;
    private final DecisionSink sink;
    /** 仲裁过程事件出口（B3）：(utteranceId, event)，装配方映射 telemetry；缺省 no-op。 */
    private final BiConsumer<String, CloudArbiterEvent> eventSink;

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

    /**
     * 双候选竞速入口。offline 以 {@code null} 表示"无命中"（调用方把 Optional 摊平）。
     *
     * @param offline     离线命令识别结果（{@code CompletableFuture<OfflineCommandHit>}，
     *                    未命中/失败 → complete(null)）
     * @param llm         LLM 语义结果
     * @param utteranceId 本段话语唯一 ID（telemetry 贯通：决策事件填真实值，由调用方从
     *                    audio_start 的端侧 utteranceId 或自增回退值传入）
     */
    public CompletableFuture<ArbiterDecision> decide(CompletableFuture<OfflineCommandHit> offline,
                                                     CompletableFuture<Reply> llm,
                                                     SessionContext ctx, String utteranceId) {
        CompletableFuture<ArbiterDecision> out = new CompletableFuture<>();
        AtomicBoolean settled = new AtomicBoolean(false);

        // 候选 1：离线命令命中 → 立即胜出，不等 LLM。
        // 注意：不再以 settled 提前 return——迟到 offline（LLM 已胜出后命中）也要发
        // received（收到 asr 命令词）+ lost（llm_already_won）事件；决策仍由 CAS 守卫。
        offline.whenComplete((hit, err) -> {
            if (err != null || hit == null) return; // 未命中/失败不发事件，不是候选
            onEvent(utteranceId, CloudArbiterEvent.received(ROUTE_NLU_TRADITIONAL));
            if (settled.compareAndSet(false, true)) {
                Reply reply = Reply.ofAction(hit.intent(), SpeakTexts.speak(hit.intent()));
                sink.log(entry(utteranceId, ROUTE_NLU_TRADITIONAL, "offline_won"));
                onEvent(utteranceId, CloudArbiterEvent.won(ROUTE_NLU_TRADITIONAL,
                        CloudArbiterEvent.Reason.PRIORITY, "offline_won"));
                out.complete(new ArbiterDecision(reply, "offline_won", hit.text()));
            } else if (settledBy(out, "llm_reply")) {
                // 迟到 offline：已有 LLM 胜出；safety 收敛后迟到不发（原因枚举无对应项）
                onEvent(utteranceId, CloudArbiterEvent.lost(ROUTE_NLU_TRADITIONAL,
                        CloudArbiterEvent.Reason.LLM_ALREADY_WON));
            }
        });

        // 候选 2：LLM 结果 → 离线已完成则立即胜出，否则起宽限期等离线。
        // 注意：不再以 settled 提前 return——迟到 LLM（命令词已胜出后到达）也要发
        // received（收到 llm 语义）+ lost（command_already_won）事件；决策仍由 CAS 守卫。
        llm.whenComplete((reply, err) -> {
            if (err != null || reply == null) return; // 失败留给 safety 兜底
            onEvent(utteranceId, CloudArbiterEvent.received(ROUTE_LLM));
            if (offline.isDone()) {
                // 离线已完成（空/失败）：没有更优候选可等 → LLM 立即胜出
                settleLlm(settled, out, reply, utteranceId);
            } else {
                // 宽限期：LLM 到达后等 offline 到 graceMs，到点未命中 → LLM 胜出
                scheduler.schedule(() -> settleLlm(settled, out, reply, utteranceId),
                        offlineGraceMs, TimeUnit.MILLISECONDS);
            }
        });

        // safety 兜底：safetyTimeoutMs 内无一收敛
        scheduler.schedule(() -> {
            if (settled.compareAndSet(false, true)) {
                sink.log(entry(utteranceId, ROUTE_LLM, "safety_timeout"));
                onEvent(utteranceId, CloudArbiterEvent.won(ROUTE_LLM,
                        CloudArbiterEvent.Reason.LLM_TIMEOUT, "safety_timeout"));
                out.complete(new ArbiterDecision(Reply.ofText(SAFETY_TEXT), "safety_timeout", null));
            }
        }, safetyTimeoutMs, TimeUnit.MILLISECONDS);
        return out;
    }

    /**
     * LLM 胜出收敛（即时与宽限期到点共用）：CAS 成功 → 胜出事件；失败 → 迟到 LLM
     * 的 lost 事件（reason {@code command_already_won}，仅在命令词已胜出时；safety
     * 收敛不触发 lost，需求原因枚举无对应项）。
     */
    private void settleLlm(AtomicBoolean settled, CompletableFuture<ArbiterDecision> out,
                           Reply reply, String utteranceId) {
        if (!settled.compareAndSet(false, true)) {
            if (settledBy(out, "offline_won")) {
                onEvent(utteranceId, CloudArbiterEvent.lost(ROUTE_LLM,
                        CloudArbiterEvent.Reason.COMMAND_ALREADY_WON));
            }
            return;
        }
        sink.log(entry(utteranceId, ROUTE_LLM, "llm_reply"));
        onEvent(utteranceId, CloudArbiterEvent.won(ROUTE_LLM,
                CloudArbiterEvent.Reason.PRIORITY, "llm_reply"));
        out.complete(new ArbiterDecision(reply, "llm_reply", null));
    }

    /** 已收敛且胜者为给定决策 reason（out 只会 complete 成功，getNow 安全）。 */
    private static boolean settledBy(CompletableFuture<ArbiterDecision> out, String reason) {
        ArbiterDecision d = out.isDone() ? out.getNow(null) : null;
        return d != null && reason.equals(d.reason());
    }

    private void onEvent(String utteranceId, CloudArbiterEvent event) {
        eventSink.accept(utteranceId, event);
    }

    /** 旧单路入口（offline 恒空）：语义与改造前一致——llm_reply / safety_timeout。 */
    public CompletableFuture<Reply> decide(String text, LlmProvider llm, SessionContext ctx, String utteranceId) {
        CompletableFuture<OfflineCommandHit> offline = CompletableFuture.completedFuture(null);
        return decide(offline, llm.chat(text, ctx, utteranceId), ctx, utteranceId)
                .thenApply(ArbiterDecision::reply);
    }

    private static DecisionEntry entry(String utteranceId, String route, String reason) {
        return new DecisionEntry(ARBITER_CLOUD, route, reason, utteranceId, System.currentTimeMillis());
    }
}
