package com.autovoice.server.gateway;

import com.autovoice.server.arbitration.DecisionSink;
import com.autovoice.server.arbitration.RaceArbiter;
import com.autovoice.server.contracts.ArbiterDecision;
import com.autovoice.server.contracts.CloudArbiterEvent;
import com.autovoice.server.contracts.DecisionEntry;
import com.autovoice.server.contracts.Intent;
import com.autovoice.server.contracts.OnlineSpeechProvider;
import com.autovoice.server.contracts.OnlineSpeechResult;
import com.autovoice.server.contracts.OnlineAudioSink;
import com.autovoice.server.contracts.OnlineAsrSink;
import com.autovoice.server.contracts.OfflineCommandHit;
import com.autovoice.server.contracts.Reply;
import com.autovoice.server.contracts.SessionContext;
import com.autovoice.server.contracts.SpeakTexts;
import com.autovoice.server.contracts.telemetry.TelemetryRecorder;
import com.autovoice.server.contracts.telemetry.TelemetryStages;
import com.autovoice.server.offlinecommand.OfflineCommandService;

import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 云端语音段处理流水线（两候选并发、固定优先级仲裁）：
 * {@code audio_end 后并行启动 { 空调离线命令识别, 编译时选中的在线语音后端 } → RaceArbiter}。
 *
 * <p>接线（按 RaceArbiter 双候选 API）：{@code arbiter.decide(offlineF, llmF, ctx, utteranceId).join()}——
 * 离线命令命中（规则映射非 unknown）立即胜出；LLM 到达后起离线宽限期等离线（离线已完成
 * 则在线候选立即胜出）；safety 兜底。Classic 返回 text/action，Omni 可直接返回 audio；
 * 普通文本播报仍走独立 {@code tts_request/tts_response} 链路。</p>
 *
 * <p>降级路径（shared/protocol.md §7.2 / §4.4）：</p>
 * <ul>
 *   <li>ASR 失败（抛 {@link AsrException} 或返回空白文本）→ 等离线窗口
 *       {@link #asrFailWaitMs}：窗口内离线命中 → 离线回复（reason {@code offline_won}）；
 *       否则兜底话术 {@link #FALLBACK_TEXT}（reason {@code asr_failed_fallback}）；</li>
 *   <li>arbiter 调用异常 → 兜底话术（reason {@code arbitration_failed_fallback}）；</li>
 *   <li>LLM 超时/异常由 RaceArbiter safety 兜底（reason {@code safety_timeout}）。</li>
 * </ul>
 *
 * <p>本方法绝不向上抛异常：任何阶段的失败都在内部收敛为可播报的 {@link SegmentResult}。</p>
 */
public final class SegmentPipeline {

    /** 兜底话术（spec §7.2，与 RaceArbiter 的 safety 兜底文本一致）。 */
    public static final String FALLBACK_TEXT = "网络开小差了，请稍后再试";

    /** 兜底决策日志 reason（protocol.md §6.1 修订表）。 */
    public static final String REASON_ASR_FAILED = "asr_failed_fallback";
    public static final String REASON_ARBITRATION_FAILED = "arbitration_failed_fallback";

    private static final String ARBITER_CLOUD = "cloud";
    /** LLM 胜出/安全兜底的路由（与 RaceArbiter sink 日志取值一致，Task 4 插桩复用）。 */
    private static final String ROUTE_LLM = "llm";
    private static final String ROUTE_NLU_TRADITIONAL = "nlu-traditional";
    private static final String REASON_OFFLINE_WON = "offline_won";

    private final OnlineSpeechProvider online;
    private final RaceArbiter arbiter;
    private final OfflineCommandService offline;
    private final long asrFailWaitMs;
    private final DecisionSink sink;
    /** 链路事件记录器（Task 4 插桩：cloud_asr / cloud_arbiter_received|won|lost；禁用时 Noop）。 */
    private final TelemetryRecorder recorder;

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SegmentPipeline.class);

    /**
     * @param asrFailWaitMs ASR 失败后等待离线结果的窗口（asr-fail-wait-ms，默认 2000）
     * @param recorder     链路事件记录器（Task 4 起；telemetry 未启用时由装配层注入 Noop）
     */
    public SegmentPipeline(OnlineSpeechProvider online, RaceArbiter arbiter,
                           OfflineCommandService offline, long asrFailWaitMs, DecisionSink sink,
                           TelemetryRecorder recorder) {
        this.online = online;
        this.arbiter = arbiter;
        this.offline = offline;
        this.asrFailWaitMs = asrFailWaitMs;
        this.sink = sink;
        this.recorder = recorder;
    }

    /**
     * 一段录音的处理结果：Classic 通常为 text/action，S2S 可带 mime/audio；
     * text 供 kind=text 下行（与 speakText 同带，端侧 parseReply 对 kind=text 强读 text 字段），
     * intent 非空时下行 kind=action；asrText = 离线胜出时的离线原文，否则 ASR 识别文本。
     */
    public record SegmentResult(String text, String speakText, Intent intent, String asrText,
                                String mime, byte[] audio, boolean streamed) {
        public SegmentResult(String text, String speakText, Intent intent, String asrText) {
            this(text, speakText, intent, asrText, null, null, false);
        }

        SegmentResult asStreamed() {
            return new SegmentResult(text, speakText, intent, asrText, mime, audio, true);
        }
    }

    public SegmentResult handleSegment(byte[] pcm, SessionContext ctx, String utteranceId) {
        return handleSegment(pcm, ctx, utteranceId, null);
    }

    /**
     * 处理一段录音（带 segmentId 快照版本）。segmentId 透传到仲裁器：
     * pending 占位事件携带它（B5），端侧据此对账当前话语——异步回调不可读调用方的
     * 可变字段（可能已被下一轮覆盖），必须走参数快照。
     */
    public SegmentResult handleSegment(byte[] pcm, SessionContext ctx, String utteranceId, String segmentId) {
        return handleSegment(pcm, ctx, utteranceId, segmentId, OnlineAudioSink.NOOP);
    }

    public SegmentResult handleSegment(byte[] pcm, SessionContext ctx, String utteranceId,
                                       String segmentId, OnlineAudioSink downstreamAudio) {
        return handleSegment(pcm, ctx, utteranceId, segmentId, downstreamAudio, OnlineAsrSink.NOOP);
    }

    public SegmentResult handleSegment(byte[] pcm, SessionContext ctx, String utteranceId,
                                       String segmentId, OnlineAudioSink downstreamAudio,
                                       OnlineAsrSink downstreamAsr) {
        // 同一份音频并发进入云端离线候选和编译时选中的在线候选。这里不做串行路由：
        // RaceArbiter 只拦截输出，空调离线命中时取消在线，否则放行在线结果。
        CompletableFuture<Optional<OfflineCommandHit>> offlineF = offline.recognize(pcm, ctx, utteranceId);
        CloudAudioGate audioGate = new CloudAudioGate(offlineF, downstreamAudio);
        long onlineStart = System.currentTimeMillis();
        final CompletableFuture<OnlineSpeechResult> onlineF;
        try {
            // ASR 事件旁路 audioGate/RaceArbiter；只让 NLU reply 与回答音频/文本受仲裁。
            onlineF = online.process(pcm, ctx, utteranceId, audioGate, downstreamAsr);
        } catch (RuntimeException e) {
            recordOnlineStartFailure(e, pcm, utteranceId, onlineStart);
            return waitOfflineFallback(offlineF, ctx, utteranceId);
        }
        CompletableFuture<Reply> replyF = onlineF.thenApply(OnlineSpeechResult::reply);
        try {
            ArbiterDecision decision = arbiter
                    .decide(offlineF.thenApply(o -> o.orElse(null)), replyF, ctx, utteranceId, segmentId)
                    .join();
            if ("offline_won".equals(decision.reason())) {
                audioGate.reject();
                onlineF.cancel(true);
                return toResult(decision, "", ctx, utteranceId);
            }
            // RaceArbiter may release online after its finite offline grace period even if
            // the offline future is still pending. Mirror that decision in the chunk gate
            // so buffered audio cannot leak later, after a full fallback reply/connection close.
            audioGate.release();
            OnlineSpeechResult onlineResult = onlineF.isCompletedExceptionally()
                    ? null : onlineF.getNow(null);
            recordOnlineSuccess(onlineResult, utteranceId, onlineStart);
            // 仲裁过程事件（received/won/lost）由 RaceArbiter 经 eventSink 发出（B3），
            // 此处不再事后补记，避免与竞速时序冲突
            SegmentResult result = toResult(decision,
                    onlineResult == null ? "" : onlineResult.asrText(), ctx, utteranceId);
            return audioGate.streamed() && "audio".equals(decision.reply().kind())
                    ? result.asStreamed() : result;
        } catch (Exception e) {
            audioGate.reject();
            LOG.error("arbitration failed (utt={}) → arbitration_failed_fallback", utteranceId, e);
            return fallback(ctx, utteranceId, REASON_ARBITRATION_FAILED);
        }
    }

    /**
     * B3 事件映射：{@link CloudArbiterEvent} → telemetry 插桩（cloud_arbiter_received /
     * cloud_arbiter_won / cloud_arbiter_lost）。静态方法：RaceArbiter 的 eventSink 由
     * 装配方（VoiceGatewayHandler）以 {@code (uid, e) -> recordArbiterEvent(recorder, uid, e)}
     * 注入；ASR 失败降级路径（不经过 RaceArbiter）在本类直调。
     *
     * @param recorder 链路事件记录器（telemetry 禁用时 Noop）
     * @param utteranceId 事件所属轮次（迟到事件可能在任何后续段之后触发，必须绑定正确轮次）
     * @param event      仲裁过程事件
     */
    static void recordArbiterEvent(TelemetryRecorder recorder, String utteranceId,
                                   CloudArbiterEvent event) {
        switch (event.kind()) {
            case RECEIVED -> recorder.record(utteranceId, TelemetryStages.CLOUD_ARBITER_RECEIVED, "info",
                    Map.of("route", event.route()));
            case WON -> recorder.record(utteranceId, TelemetryStages.CLOUD_ARBITER_WON, "info",
                    Map.of("route", event.route(), "reason", event.reason().wire(),
                            "decision", event.decisionReason()));
            case LOST -> recorder.record(utteranceId, TelemetryStages.CLOUD_ARBITER_LOST, "warn",
                    Map.of("route", event.route(), "reason", event.reason().wire()));
            case PENDING -> recorder.record(utteranceId, TelemetryStages.CLOUD_ARBITER_PENDING, "info",
                    Map.of("route", event.route(), "reason", event.reason().wire()));
        }
    }

    private void recordOnlineStartFailure(RuntimeException error, byte[] pcm,
                                          String utteranceId, long start) {
        Throwable cause = error.getCause() == null ? error : error.getCause();
        LOG.error("{} online speech failed before candidate start (pcm={}B utt={})",
                online.id(), pcm.length, utteranceId, cause);
        recorder.record(utteranceId, TelemetryStages.CLOUD_ASR, "warn",
                Map.of("backend", online.id(), "error", String.valueOf(cause.getMessage()),
                        "durationMs", elapsedMs(start)));
    }

    private void recordOnlineSuccess(OnlineSpeechResult result, String utteranceId, long start) {
        if (result == null || result.asrText().isBlank()) return;
        recorder.record(utteranceId, TelemetryStages.CLOUD_ASR, "info",
                Map.of("backend", online.id(), "text", result.asrText(), "durationMs", elapsedMs(start)));
    }

    /** 耗时（毫秒，最小 1 防止 0 被面板当缺省值）。 */
    private static long elapsedMs(long start) {
        return Math.max(1, System.currentTimeMillis() - start);
    }

    /**
     * ASR 失败路径：等离线窗口（asrFailWaitMs）内的离线结果——命中则离线回复
     * （decision reason=offline_won，route=nlu-traditional），否则 asr_failed_fallback。
     */
    private SegmentResult waitOfflineFallback(CompletableFuture<Optional<OfflineCommandHit>> offlineF,
                                              SessionContext ctx, String utteranceId) {
        Optional<OfflineCommandHit> hit;
        try {
            hit = offlineF.get(asrFailWaitMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            LOG.warn("offline miss after ASR failure (utt={}): {}", utteranceId,
                    String.valueOf(e.getMessage()));
            hit = Optional.empty();
        }
        if (hit.isPresent()) {
            LOG.info("ASR failed but offline command hit: \"{}\" (utt={})", hit.get().text(), utteranceId);
            sink.log(new DecisionEntry(ARBITER_CLOUD, ROUTE_NLU_TRADITIONAL, REASON_OFFLINE_WON,
                    utteranceId, System.currentTimeMillis()));
            recordArbiterEvent(recorder, utteranceId, CloudArbiterEvent.received(ROUTE_NLU_TRADITIONAL));
            recordArbiterEvent(recorder, utteranceId, CloudArbiterEvent.won(ROUTE_NLU_TRADITIONAL,
                    CloudArbiterEvent.Reason.PRIORITY, REASON_OFFLINE_WON));
            return offlineHitResult(hit.get());
        }
        return fallback(ctx, utteranceId, REASON_ASR_FAILED);
    }

    /** 仲裁结果 → SegmentResult；按 kind 分支，无音频语义（text/action 双形态）。 */
    private SegmentResult toResult(ArbiterDecision decision, String asrText,
                                   SessionContext ctx, String utteranceId) {
        Reply reply = decision.reply();
        // asrText：离线胜出用离线原文（更贴近用户语音），其余路径用 ASR 文本
        String textForAsr = decision.offlineText() != null ? decision.offlineText() : asrText;
        switch (reply.kind()) {
            case "action" -> {
                Intent intent = reply.intent();
                String speakText = reply.speakText();
                if (speakText == null || speakText.isBlank()) {
                    return fallback(ctx, utteranceId, REASON_ARBITRATION_FAILED);
                }
                return new SegmentResult(null, speakText, intent, textForAsr);
            }
            case "text" -> {
                String text = reply.text();
                if (text == null || text.isBlank()) {
                    return fallback(ctx, utteranceId, REASON_ARBITRATION_FAILED);
                }
                // text + speakText 同带（端侧 parseReply 对 kind=text 强读 text 字段）
                return new SegmentResult(text, text, null, textForAsr);
            }
            case "audio" -> {
                if (reply.mime() == null || reply.data() == null || reply.data().length == 0) {
                    return fallback(ctx, utteranceId, REASON_ARBITRATION_FAILED);
                }
                return new SegmentResult(null, reply.speakText(), reply.intent(), textForAsr,
                        reply.mime(), reply.data(), false);
            }
            default -> {
                // 防御：未知 kind → 文本化
                String speakText = reply.speakText() != null ? reply.speakText() : reply.text();
                if (speakText == null || speakText.isBlank()) {
                    return fallback(ctx, utteranceId, REASON_ARBITRATION_FAILED);
                }
                return new SegmentResult(speakText, speakText, null, textForAsr);
            }
        }
    }

    /** 离线命中 → 回复（speakText 由 SpeakTexts 模板生成，与 LLM 路径一致）。 */
    private static SegmentResult offlineHitResult(OfflineCommandHit hit) {
        return new SegmentResult(null, SpeakTexts.speak(hit.intent()), hit.intent(), hit.text());
    }

    /**
     * 兜底收敛：记一条决策事件（reason 区分 ASR / 仲裁失败）+ llm 路超时胜出事件
     * （B3 映射：safety 兜底同源，reason=llm_timeout，decision=兜底 reason），返回兜底话术。
     */
    private SegmentResult fallback(SessionContext ctx, String utteranceId, String reason) {
        sink.log(new DecisionEntry(ARBITER_CLOUD, ROUTE_LLM, reason, utteranceId, System.currentTimeMillis()));
        recordArbiterEvent(recorder, utteranceId, CloudArbiterEvent.won(ROUTE_LLM,
                CloudArbiterEvent.Reason.LLM_TIMEOUT, reason));
        return new SegmentResult(null, FALLBACK_TEXT, null, null); // 兜底无识别文本
    }

    /** 服务端云端仲裁门：离线明确未命中/失败前只缓存在线音频事件。 */
    private static final class CloudAudioGate implements OnlineAudioSink {
        private enum State { PENDING, RELEASED, REJECTED }

        private final OnlineAudioSink downstream;
        private final List<Consumer<OnlineAudioSink>> buffered = new ArrayList<>();
        private State state = State.PENDING;
        private volatile boolean streamed;

        CloudAudioGate(CompletableFuture<Optional<OfflineCommandHit>> offline,
                       OnlineAudioSink downstream) {
            this.downstream = downstream == null ? OnlineAudioSink.NOOP : downstream;
            offline.whenComplete((hit, error) -> resolve(error == null && hit != null && hit.isPresent()
                    && isAirConControl(hit.get().intent())));
        }

        private void resolve(boolean offlineWon) {
            synchronized (this) {
                if (state != State.PENDING) return;
                state = offlineWon ? State.REJECTED : State.RELEASED;
                if (offlineWon) {
                    buffered.clear();
                    return;
                }
                // Keep the lock while draining: a concurrently arriving chunk must not
                // overtake the buffered start event after state changes to RELEASED.
                buffered.forEach(event -> event.accept(downstream));
                buffered.clear();
            }
        }

        void release() {
            resolve(false);
        }

        void reject() {
            resolve(true);
        }

        private void submit(Consumer<OnlineAudioSink> event) {
            boolean release;
            synchronized (this) {
                if (state == State.REJECTED) return;
                if (state == State.PENDING) {
                    buffered.add(event);
                    return;
                }
                release = true;
            }
            if (release) event.accept(downstream);
        }

        @Override
        public void onStart(int sampleRate, int channels, String encoding) {
            submit(sink -> {
                streamed = true;
                sink.onStart(sampleRate, channels, encoding);
            });
        }

        @Override
        public void onChunk(byte[] pcm) {
            byte[] snapshot = pcm.clone();
            submit(sink -> sink.onChunk(snapshot));
        }

        @Override
        public void onReplyText(String text, boolean isFinal) {
            submit(sink -> sink.onReplyText(text, isFinal));
        }

        @Override
        public void onComplete(String speakText, Intent intent) {
            submit(sink -> sink.onComplete(speakText, intent));
        }

        @Override
        public void onComplete(String speakText, Intent intent, String asrText) {
            submit(sink -> sink.onComplete(speakText, intent, asrText));
        }

        @Override
        public void onError(Throwable error) {
            submit(sink -> sink.onError(error));
        }

        synchronized boolean streamed() {
            return streamed;
        }

        private static boolean isAirConControl(Intent intent) {
            if (intent == null || !"climate".equals(intent.domain())) return false;
            return "power_on".equals(intent.intent()) || "power_off".equals(intent.intent())
                    || "set_temperature".equals(intent.intent());
        }
    }
}
