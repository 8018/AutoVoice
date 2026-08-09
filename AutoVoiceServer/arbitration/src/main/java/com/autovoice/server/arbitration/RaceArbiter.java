package com.autovoice.server.arbitration;

import com.autovoice.server.contracts.DecisionEntry;
import com.autovoice.server.contracts.LlmProvider;
import com.autovoice.server.contracts.Reply;
import com.autovoice.server.contracts.SessionContext;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 云端仲裁器（单路 LLM + safety 兜底）：
 * 识别文本直接交给 LLM（语义结果由模型 function calling 产出，见 DeepSeekLlmProvider
 * 的 car_control skill），并把决策写入 {@link DecisionSink}。
 *
 * <p>收敛规则：</p>
 * <ul>
 *   <li>llm 在 {@code safetyTimeoutMs} 内出结果 → 用它（llm_reply）；</li>
 *   <li>llm 超时/异常 → 兜底文本回复（safety_timeout）。</li>
 * </ul>
 *
 * <p>并发实现：{@link AtomicBoolean} 作单赢家守卫（首个 CAS 成功者 complete 结果并写日志），
 * safety 计时用 {@link ScheduledExecutorService} 的 schedule 任务，计时任务触发时
 * 再查 {@code llmF.isDone()} 防双写。</p>
 *
 * <p>说明：原 NLU ∥ LLM 竞速（spec §5.2）已随讯飞 AIUI text_ai 端点下线退役——
 * 语义路由 LLM function calling 承担，仲裁退化为单路 + safety 兜底。</p>
 */
public final class RaceArbiter {

    private static final String SAFETY_TEXT = "网络开小差了，请稍后再试";
    private static final String ARBITER_CLOUD = "cloud";
    private static final String ROUTE_LLM = "llm";

    private final long safetyTimeoutMs;
    private final ScheduledExecutorService scheduler;
    private final DecisionSink sink;

    public RaceArbiter(long safetyTimeoutMs, ScheduledExecutorService scheduler, DecisionSink sink) {
        this.safetyTimeoutMs = safetyTimeoutMs;
        this.scheduler = scheduler;
        this.sink = sink;
    }

    public CompletableFuture<Reply> decide(String text, LlmProvider llm, SessionContext ctx) {
        CompletableFuture<Reply> out = new CompletableFuture<>();
        AtomicBoolean settled = new AtomicBoolean(false);
        CompletableFuture<Reply> llmF = llm.chat(text, ctx);
        llmF.whenComplete((reply, err) -> {
            if (err != null || reply == null || settled.get()) return; // 失败留给 safety 兜底
            if (settled.compareAndSet(false, true)) {
                sink.log(entry(ctx, "llm_reply"));
                out.complete(reply);
            }
        });
        scheduler.schedule(() -> {
            if (settled.compareAndSet(false, true)) {
                sink.log(entry(ctx, "safety_timeout"));
                out.complete(Reply.ofText(SAFETY_TEXT));
            }
        }, safetyTimeoutMs, TimeUnit.MILLISECONDS);
        return out;
    }

    private static DecisionEntry entry(SessionContext ctx, String reason) {
        return new DecisionEntry(ARBITER_CLOUD, ROUTE_LLM, reason, ctx.sessionId(), System.currentTimeMillis());
    }
}
