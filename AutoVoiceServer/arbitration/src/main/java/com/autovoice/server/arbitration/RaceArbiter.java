package com.autovoice.server.arbitration;

import com.autovoice.server.contracts.DecisionEntry;
import com.autovoice.server.contracts.Intent;
import com.autovoice.server.contracts.LlmProvider;
import com.autovoice.server.contracts.NluProvider;
import com.autovoice.server.contracts.Reply;
import com.autovoice.server.contracts.SessionContext;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 云端竞速仲裁器（spec §5.2 收敛策略）：
 * NLU 与传统 LLM 并行竞速，按"单赢家"规则收敛，并把决策写入 {@link DecisionSink}。
 *
 * <p>收敛规则：</p>
 * <ul>
 *   <li>nlu 先出且非拒识 → 立即用它（action 回复，speakText 由模板生成）；</li>
 *   <li>llm 先出 → 给 nlu 最多 {@code nluGraceMs} 宽限期：
 *       nlu 在宽限内到达且非拒识 → 用 nlu（llm_first_wait_nlu_arrived）；
 *       nlu 在宽限内到达但拒识 → 用 llm（nlu_rejected_use_llm）；
 *       宽限超时 → 用 llm（llm_first_wait_timeout）；</li>
 *   <li>两者都未在 {@code safetyTimeoutMs} 内出结果 → 兜底文本回复（safety_timeout）。</li>
 * </ul>
 *
 * <p>并发实现：{@link AtomicBoolean} 作单赢家守卫（首个 CAS 成功者 complete 结果并写日志），
 * grace/safety 计时用 {@link ScheduledExecutorService} 的 schedule 任务，
 * 计时任务触发时再查 {@code nluF.isDone()} 防双写。</p>
 *
 * <p>期限守卫：safety 期限（{@code safetyTimeoutMs}）后只允许兜底收敛——nlu/llm 回调与
 * grace 定时器在 CAS/settle 前都检查期限，超期一律让位 safety 定时器，保证决策日志恰一条。</p>
 */
public final class RaceArbiter {

    /** 车控 domain → 播报模板；未知 domain 兜底 "已为您执行"。 */
    private static final Map<String, String> DOMAIN_SPEECH = Map.of(
            "climate", "已为您执行空调指令"
    );

    private static final String SAFETY_TEXT = "网络开小差了，请稍后再试";
    private static final String ARBITER_CLOUD = "cloud";
    private static final String ROUTE_NLU = "nlu-traditional";
    private static final String ROUTE_LLM = "llm";

    private final long nluGraceMs;
    private final long safetyTimeoutMs;
    private final ScheduledExecutorService scheduler;
    private final DecisionSink sink;

    public RaceArbiter(long nluGraceMs, long safetyTimeoutMs,
                       ScheduledExecutorService scheduler, DecisionSink sink) {
        this.nluGraceMs = nluGraceMs;
        this.safetyTimeoutMs = safetyTimeoutMs;
        this.scheduler = scheduler;
        this.sink = sink;
    }

    public CompletableFuture<Reply> decide(String text, NluProvider nlu, LlmProvider llm, SessionContext ctx) {
        CompletableFuture<Reply> out = new CompletableFuture<>();
        AtomicBoolean settled = new AtomicBoolean(false);
        long safetyDeadlineMs = System.currentTimeMillis() + safetyTimeoutMs;
        CompletableFuture<Intent> nluF = nlu.understand(text, ctx);
        CompletableFuture<Reply> llmF = llm.chat(text, ctx);
        nluF.whenComplete((intent, err) -> {
            if (err != null || intent == null || intent.isUnknown()) return; // 拒识留给 LLM
            if (System.currentTimeMillis() >= safetyDeadlineMs) {
                // 已过 safety 兜底期限：nlu 迟到（如测试中 provider 占满线程池、到期的
                // safety 定时任务未能及时执行），本分支代表兜底收敛，不与应用场景抢赢。
                if (settled.compareAndSet(false, true)) {
                    sink.log(entry(ctx, "safety_timeout"));
                    out.complete(Reply.ofText(SAFETY_TEXT));
                }
                return;
            }
            if (settled.compareAndSet(false, true)) {
                sink.log(entry(ctx, "nlu_first"));
                out.complete(Reply.ofAction(intent, intentToSpeak(intent))); // 车控动作回复；text 意图场景由调用方转换为文本
            }
        });
        llmF.whenComplete((reply, err) -> {
            // 与 nlu 守卫对称：safety 期限后 llm 迟到，一律让位 safety 定时器兜底收敛。
            if (err != null || settled.get() || System.currentTimeMillis() >= safetyDeadlineMs) return;
            if (nluF.isDone()) { // nlu 已有结论
                try { Intent i = nluF.get();
                    if (i == null || i.isUnknown()) {
                        if (settled.compareAndSet(false, true)) { sink.log(entry(ctx, "nlu_rejected_use_llm")); out.complete(reply); }
                    } // 非拒识：nlu 分支已 settle 或即将 settle，忽略
                } catch (Exception e) { if (settled.compareAndSet(false, true)) out.complete(reply); }
                return;
            }
            // llm 先到，给 nlu 宽限期
            scheduler.schedule(() -> {
                // 超期或已被其他分支 settle 一律让位：先查期限，再 CAS 收敛（杜绝双写窗口）。
                if (settled.get() || System.currentTimeMillis() >= safetyDeadlineMs) return;
                if (nluF.isDone()) {
                    try { Intent i = nluF.get();
                        if (i == null || i.isUnknown()) {
                            if (settled.compareAndSet(false, true)) { sink.log(entry(ctx, "nlu_rejected_use_llm")); out.complete(reply); }
                        } else {
                            if (settled.compareAndSet(false, true)) { sink.log(entry(ctx, "llm_first_wait_nlu_arrived")); out.complete(Reply.ofAction(i, intentToSpeak(i))); }
                        }
                    } catch (Exception e) { if (settled.compareAndSet(false, true)) out.complete(reply); }
                } else {
                    if (settled.compareAndSet(false, true)) { sink.log(entry(ctx, "llm_first_wait_timeout")); out.complete(reply); }
                }
            }, nluGraceMs, TimeUnit.MILLISECONDS);
        });
        scheduler.schedule(() -> { if (settled.compareAndSet(false, true)) { sink.log(entry(ctx, "safety_timeout")); out.complete(Reply.ofText(SAFETY_TEXT)); } }, safetyTimeoutMs, TimeUnit.MILLISECONDS);
        return out;
    }

    /** reason → route 映射：nlu 路线 vs llm 路线。 */
    private static DecisionEntry entry(SessionContext ctx, String reason) {
        String route = switch (reason) {
            case "nlu_first", "llm_first_wait_nlu_arrived" -> ROUTE_NLU;
            default -> ROUTE_LLM; // nlu_rejected_use_llm / llm_first_wait_timeout / safety_timeout
        };
        return new DecisionEntry(ARBITER_CLOUD, route, reason, ctx.sessionId(), System.currentTimeMillis());
    }

    /** 车控 Intent → 一句播报文本（domain 模板映射，未知 domain 兜底）。 */
    static String intentToSpeak(Intent intent) {
        if (intent == null || intent.isUnknown()) return "已为您执行";
        return DOMAIN_SPEECH.getOrDefault(intent.domain(), "已为您执行");
    }
}
