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

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/**
 * 云端仲裁器（双候选竞速）：离线命令识别 ∥ ASR→LLM，并把决策写入 {@link DecisionSink}。
 *
 * <p>收敛规则（spec §5.2 修订，离线命令词为"传统链路"）：</p>
 * <ul>
 *   <li><b>offline 命中</b>（识别到命令词且规则映射非 unknown，且是空调控制
 *       ——能力分级 2026-08-15：云端命令词只负责空调，见 {@link #isAirConControl}）
 *       → 立即胜出，不等 LLM（route = {@code nlu-traditional}，reason =
 *       {@code offline_won}）；非空调命中按未命中处理，走 LLM 优先路径；</li>
 *   <li><b>LLM 到达</b>：若 offline 已完成（无论命中与否）→ LLM 立即胜出；否则起
 *       {@code offlineGraceMs} 宽限期等 offline——宽限期内 offline 命中 → 离线胜出，
 *       到点未命中 → LLM 胜出（reason = {@code llm_reply}）；</li>
 *   <li><b>safety 兜底</b>：{@code safetyTimeoutMs} 内无一收敛 → 兜底文本
 *       （reason = {@code safety_timeout}）；</li>
 *   <li><b>void（最新会话拦截）</b>：装配方调用 {@link #voidTurn}（cancel_turn /
 *       superseded）→ 立即按对应 reason 收敛——不取消候选、不写决策日志、不发仲裁事件；
 *       候选自然完成后经 settled CAS / settledByVoid 守卫静默丢弃（拦截而非取消）；</li>
 *   <li>任一路径胜出后不取消另一候选；迟到候选由 settled CAS 闸门拦截，
 *       只可记录 lost 事件，不得执行或播报。</li>
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

    /** 在途轮登记（并发：decide 在连接串行工作线程，voidTurn 在 WS 消息线程）。 */
    private final AtomicReference<ActiveTurn> activeTurn = new AtomicReference<>();
    /** 先于 decide 注册到达的 void 标记（uid → reason）；decide 注册时消费，clearVoid 兜底清理。 */
    private final Map<String, String> pendingVoids = new ConcurrentHashMap<>();

    /** 在途轮句柄：voidTurn 需要 settled CAS、out 与双候选；按 utteranceId 匹配。 */
    private record ActiveTurn(String utteranceId, AtomicBoolean settled,
                              CompletableFuture<ArbiterDecision> out,
                              CompletableFuture<OfflineCommandHit> offline,
                              CompletableFuture<Reply> llm) {
    }

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

    /** 兼容协议中显式 void 本轮输出的收敛原因；正常仲裁胜出不发送此事件。 */
    public static final String REASON_CANCEL_TURN = "cancel_turn";
    /** 被更新话语取代的收敛原因（新 audio_start 携带不同 utteranceId，端侧 isStale 同构）。 */
    public static final String REASON_SUPERSEDED = "superseded";

    /**
     * 云端"最新会话拦截"（与端侧 OnDeviceRaceArbiter.isStale 同构）：在途轮 utteranceId
     * 匹配 → 立即按 reason 收敛；尚未注册 → 记录标记，decide 注册时收敛。
     * 被 void 的轮不写决策日志、不发仲裁事件、不取消候选、无下行帧（拦截而非取消——
     * 客户端已自行裁决/已推进，服务端候选自然完成后被仲裁拦截丢弃）。
     *
     * @return true = void 被采纳（已收敛或将在注册时收敛）；false = 在途轮属于其他
     *         utteranceId（防御路径，正常串行流程不会发生）
     */
    public boolean voidTurn(String utteranceId, String reason) {
        if (utteranceId == null) return false;
        ActiveTurn turn = activeTurn.get();
        if (turn != null) {
            return utteranceId.equals(turn.utteranceId()) && settleVoided(turn, reason);
        }
        pendingVoids.put(utteranceId, reason);
        return true;
    }

    /** 清理未及注册的 void 标记（worker 早退 / 已完成的轮由装配方在 finally 兜底调用）。 */
    public void clearVoid(String utteranceId) {
        if (utteranceId != null) pendingVoids.remove(utteranceId);
    }

    /** 内部收敛：CAS 命中 → 以给定原因 complete out（不取消候选、无决策日志、无仲裁事件）。 */
    private boolean settleVoided(ActiveTurn turn, String reason) {
        if (!turn.settled().compareAndSet(false, true)) return false;
        turn.out().complete(new ArbiterDecision(null, reason, null));
        return true;
    }

    /** 本轮是否已被 void（cancel_turn / superseded）——迟到候选据此静默丢弃。 */
    private static boolean settledByVoid(CompletableFuture<ArbiterDecision> out) {
        if (!out.isDone()) return false;
        ArbiterDecision d = out.getNow(null);
        return d != null && (REASON_CANCEL_TURN.equals(d.reason()) || REASON_SUPERSEDED.equals(d.reason()));
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
        return decide(offline, llm, ctx, utteranceId, null);
    }

    /**
     * 双候选竞速入口（带 segmentId 快照的版本）。offline 以 {@code null} 表示"无命中"
     * （调用方把 Optional 摊平）。
     *
     * @param offline     离线命令识别结果（{@code CompletableFuture<OfflineCommandHit>}，
     *                    未命中/失败 → complete(null)）
     * @param llm         LLM 语义结果
     * @param utteranceId 本段话语唯一 ID（telemetry 贯通：决策事件填真实值，由调用方从
     *                    audio_start 的端侧 utteranceId 或自增回退值传入）
     * @param segmentId   本段话语快照（pending 占位消息回显给端侧对账用，可空——不可读
     *                    调用方可变字段：whenComplete 回调异步，可能已被下一轮覆盖）
     */
    public CompletableFuture<ArbiterDecision> decide(CompletableFuture<OfflineCommandHit> offline,
                                                     CompletableFuture<Reply> llm,
                                                     SessionContext ctx, String utteranceId, String segmentId) {
        CompletableFuture<ArbiterDecision> out = new CompletableFuture<>();
        AtomicBoolean settled = new AtomicBoolean(false);
        ActiveTurn turn = new ActiveTurn(utteranceId, settled, out, offline, llm);
        activeTurn.set(turn);
        // 先于注册到达的 void（注册前窗口：offline.recognize / online.process 起动中）：
        // 立即按记录的原因收敛（settleVoided 内部 CAS 幂等）
        String voidReason = utteranceId != null ? pendingVoids.remove(utteranceId) : null;
        if (voidReason != null) {
            settleVoided(turn, voidReason);
        }

        // 候选 1：离线命令命中 → 立即胜出，不等 LLM。
        // 能力分级（2026-08-15）：只认空调控制（climate 域 power_on/power_off/
        // set_temperature）——非空调命中（window/misc/防御）按未命中处理：不发事件、
        // 不参与 CAS，走 LLM 优先路径；此时若 LLM 尚未完成 → 发 pending 占位事件
        // （B5：装配方据此下发"处理中"消息，protocol.md §4.8）。
        // 仍保留 CAS 守卫，处理恰好同时完成的竞态。
        offline.whenComplete((hit, err) -> {
            if (err != null) return;
            if (settledByVoid(out)) return; // 已被 void：迟到离线候选静默丢弃（拦截而非取消）
            if (hit == null || !isAirConControl(hit.intent())) {
                // 未命中 / 非空调命中 + LLM 尚未完成 → 处理中占位（至多一次：whenComplete 只回调一次）
                if (!llm.isDone()) {
                    onEvent(utteranceId, CloudArbiterEvent.pending(ROUTE_LLM, segmentId));
                }
                return;
            }
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
        // 仍保留 CAS 守卫，处理恰好同时完成的竞态。
        llm.whenComplete((reply, err) -> {
            if (err != null || reply == null) return; // 失败留给 safety 兜底
            if (settledByVoid(out)) return; // 已被 void：迟到 LLM 候选静默丢弃（拦截而非取消）
            onEvent(utteranceId, CloudArbiterEvent.received(ROUTE_LLM));
            if (offline.isDone()) {
                // 离线已完成（空/失败）：没有更优候选可等 → LLM 立即胜出
                settleLlm(settled, out, reply, utteranceId, offline);
            } else {
                // 宽限期：LLM 到达后等 offline 到 graceMs，到点未命中 → LLM 胜出
                scheduler.schedule(() -> settleLlm(settled, out, reply, utteranceId, offline),
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
        // 收敛即注销在途轮（complete 的线程上同步执行，compareAndSet 防误清下一轮登记）
        out.whenComplete((d, e) -> activeTurn.compareAndSet(turn, null));
        return out;
    }

    /**
     * 能力分级（2026-08-15）：云端命令词只负责空调——空调控制（climate 域
     * power_on / power_off / set_temperature）命中才直接胜出；其他领域命中
     * （window/misc 等）视为未命中，走 LLM 优先路径。
     */
    private static boolean isAirConControl(Intent i) {
        if (!"climate".equals(i.domain())) return false;
        String intent = i.intent();
        return "power_on".equals(intent) || "power_off".equals(intent) || "set_temperature".equals(intent);
    }

    /**
     * LLM 胜出收敛（即时与宽限期到点共用）：CAS 成功 → 胜出事件；若另一候选
     * 已收敛导致 CAS 失败，则仅在命令词已胜出时发 lost 事件。
     */
    private void settleLlm(AtomicBoolean settled, CompletableFuture<ArbiterDecision> out,
                           Reply reply, String utteranceId,
                           CompletableFuture<OfflineCommandHit> offline) {
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
