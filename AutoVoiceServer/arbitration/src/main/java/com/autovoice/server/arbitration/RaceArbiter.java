package com.autovoice.server.arbitration;

import com.autovoice.server.contracts.ArbiterDecision;
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

    public RaceArbiter(long safetyTimeoutMs, ScheduledExecutorService scheduler, DecisionSink sink) {
        this(safetyTimeoutMs, DEFAULT_OFFLINE_GRACE_MS, scheduler, sink);
    }

    public RaceArbiter(long safetyTimeoutMs, long offlineGraceMs,
                       ScheduledExecutorService scheduler, DecisionSink sink) {
        this.safetyTimeoutMs = safetyTimeoutMs;
        this.offlineGraceMs = offlineGraceMs;
        this.scheduler = scheduler;
        this.sink = sink;
    }

    /**
     * 双候选竞速入口。offline 以 {@code null} 表示"无命中"（调用方把 Optional 摊平）。
     *
     * @param offline 离线命令识别结果（{@code CompletableFuture<OfflineCommandHit>}，
     *                未命中/失败 → complete(null)）
     * @param llm     LLM 语义结果
     */
    public CompletableFuture<ArbiterDecision> decide(CompletableFuture<OfflineCommandHit> offline,
                                                     CompletableFuture<Reply> llm,
                                                     SessionContext ctx) {
        CompletableFuture<ArbiterDecision> out = new CompletableFuture<>();
        AtomicBoolean settled = new AtomicBoolean(false);

        // 候选 1：离线命令命中 → 立即胜出，不等 LLM
        offline.whenComplete((hit, err) -> {
            if (err != null || hit == null || settled.get()) return;
            if (settled.compareAndSet(false, true)) {
                Reply reply = Reply.ofAction(hit.intent(), SpeakTexts.speak(hit.intent()));
                sink.log(entry(ctx, ROUTE_NLU_TRADITIONAL, "offline_won"));
                out.complete(new ArbiterDecision(reply, "offline_won", hit.text()));
            }
        });

        // 候选 2：LLM 结果 → 离线已完成则立即胜出，否则起宽限期等离线
        llm.whenComplete((reply, err) -> {
            if (err != null || reply == null || settled.get()) return; // 失败留给 safety 兜底
            if (offline.isDone()) {
                // 离线已完成（空/失败）：没有更优候选可等 → LLM 立即胜出
                if (settled.compareAndSet(false, true)) {
                    sink.log(entry(ctx, ROUTE_LLM, "llm_reply"));
                    out.complete(new ArbiterDecision(reply, "llm_reply", null));
                }
            } else {
                // 宽限期：LLM 到达后等 offline 到 graceMs，到点未命中 → LLM 胜出
                scheduler.schedule(() -> {
                    if (settled.compareAndSet(false, true)) {
                        sink.log(entry(ctx, ROUTE_LLM, "llm_reply"));
                        out.complete(new ArbiterDecision(reply, "llm_reply", null));
                    }
                }, offlineGraceMs, TimeUnit.MILLISECONDS);
            }
        });

        // safety 兜底：safetyTimeoutMs 内无一收敛
        scheduler.schedule(() -> {
            if (settled.compareAndSet(false, true)) {
                sink.log(entry(ctx, ROUTE_LLM, "safety_timeout"));
                out.complete(new ArbiterDecision(Reply.ofText(SAFETY_TEXT), "safety_timeout", null));
            }
        }, safetyTimeoutMs, TimeUnit.MILLISECONDS);
        return out;
    }

    /** 旧单路入口（offline 恒空）：语义与改造前一致——llm_reply / safety_timeout。 */
    public CompletableFuture<Reply> decide(String text, LlmProvider llm, SessionContext ctx) {
        CompletableFuture<OfflineCommandHit> offline = CompletableFuture.completedFuture(null);
        return decide(offline, llm.chat(text, ctx), ctx).thenApply(ArbiterDecision::reply);
    }

    private static DecisionEntry entry(SessionContext ctx, String route, String reason) {
        return new DecisionEntry(ARBITER_CLOUD, route, reason, ctx.sessionId(), System.currentTimeMillis());
    }
}
