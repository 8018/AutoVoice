package com.autovoice.server.gateway;

import com.autovoice.server.arbitration.DecisionSink;
import com.autovoice.server.arbitration.RaceArbiter;
import com.autovoice.server.contracts.ArbiterDecision;
import com.autovoice.server.contracts.AsrException;
import com.autovoice.server.contracts.AsrProvider;
import com.autovoice.server.contracts.DecisionEntry;
import com.autovoice.server.contracts.Intent;
import com.autovoice.server.contracts.LlmProvider;
import com.autovoice.server.contracts.OfflineCommandHit;
import com.autovoice.server.contracts.Reply;
import com.autovoice.server.contracts.SessionContext;
import com.autovoice.server.contracts.SpeakTexts;
import com.autovoice.server.offlinecommand.OfflineCommandService;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 云端语音段处理流水线（spec §5.2 修订，双候选竞速链路）：
 * {@code audio_end 后并行启动 { 离线命令识别 , ASR → LLM } → RaceArbiter 竞速}。
 *
 * <p>接线（按 RaceArbiter 双候选 API）：{@code arbiter.decide(offlineF, llmF, ctx, utteranceId).join()}——
 * 离线命令命中（规则映射非 unknown）立即胜出；LLM 到达后起离线宽限期等离线（离线已完成
 * 则 LLM 立即胜出）；safety 兜底。回复只携带语义（intent/speakText），<b>不再携带音频</b>
 * ——TTS 已解耦为独立 {@code tts_request/tts_response} 链路（S4）。</p>
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
    private static final String ROUTE_CLOUD = "cloud";
    private static final String ROUTE_NLU_TRADITIONAL = "nlu-traditional";
    private static final String REASON_OFFLINE_WON = "offline_won";

    private final AsrProvider asr;
    private final RaceArbiter arbiter;
    private final LlmProvider llm;
    private final OfflineCommandService offline;
    private final long asrFailWaitMs;
    private final DecisionSink sink;

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SegmentPipeline.class);

    /**
     * @param asrFailWaitMs ASR 失败后等待离线结果的窗口（asr-fail-wait-ms，默认 2000）
     */
    public SegmentPipeline(AsrProvider asr, RaceArbiter arbiter, LlmProvider llm,
                           OfflineCommandService offline, long asrFailWaitMs, DecisionSink sink) {
        this.asr = asr;
        this.arbiter = arbiter;
        this.llm = llm;
        this.offline = offline;
        this.asrFailWaitMs = asrFailWaitMs;
        this.sink = sink;
    }

    /**
     * 一段录音的处理结果（TTS 解耦后不再携带音频）：
     * text 供 kind=text 下行（与 speakText 同带，端侧 parseReply 对 kind=text 强读 text 字段），
     * intent 非空时下行 kind=action；asrText = 离线胜出时的离线原文，否则 ASR 识别文本。
     */
    public record SegmentResult(String text, String speakText, Intent intent, String asrText) {
    }

    public SegmentResult handleSegment(byte[] pcm, SessionContext ctx, String utteranceId) {
        // 并行启动：离线命令识别（异步）∥ ASR（同步）
        CompletableFuture<Optional<OfflineCommandHit>> offlineF = offline.recognize(pcm, ctx);
        String text = transcribe(pcm, ctx, utteranceId);
        if (text == null) {
            // ASR 失败：等离线窗口，窗口内命中则离线兜底，否则 asr_failed_fallback
            return waitOfflineFallback(offlineF, ctx, utteranceId);
        }
        CompletableFuture<Reply> llmF = llm.chat(text, ctx);
        try {
            ArbiterDecision decision = arbiter
                    .decide(offlineF.thenApply(o -> o.orElse(null)), llmF, ctx, utteranceId)
                    .join();
            return toResult(decision, text, ctx, utteranceId);
        } catch (Exception e) {
            LOG.error("arbitration failed (utt={}) → arbitration_failed_fallback", utteranceId, e);
            return fallback(ctx, utteranceId, REASON_ARBITRATION_FAILED);
        }
    }

    /** ASR：失败或空白识别结果一律返回 null（决策日志由调用方按离线窗口后统一收敛）。 */
    private String transcribe(byte[] pcm, SessionContext ctx, String utteranceId) {
        // 诊断：ASR 失败被兜底吞掉后 server 无日志可查（端侧只看到 asr_failed_fallback）——
        // 失败路径必须留痕：pcm 长度 + 异常/空白原因
        LOG.info("ASR start: pcm={}B ({}ms) utt={}", pcm.length, pcm.length * 1000 / (2 * 16000), utteranceId);
        try {
            String text = asr.transcribe(pcm, ctx);
            if (text == null || text.isBlank()) {
                LOG.warn("ASR returned blank text (pcm={}B utt={})", pcm.length, utteranceId);
                dumpPcm(pcm, utteranceId); // 落盘空白段，回放定位音频内容问题（端侧双 ASR 全空）
                return null;
            }
            LOG.info("ASR ok: \"{}\" (utt={})", text, utteranceId);
            dumpPcm(pcm, "ok-" + utteranceId); // 诊断：成功轮与失败轮音频对比
            return text;
        } catch (Exception e) {
            LOG.error("ASR failed (pcm={}B utt={})", pcm.length, utteranceId, e);
            dumpPcm(pcm, utteranceId);
            return null;
        }
    }

    /** 诊断：ASR 段 PCM 落盘 /tmp/asr-<kind>-<utt>-<ts>.pcm，回放分析音频内容。 */
    private static void dumpPcm(byte[] pcm, String kindAndUtt) {
        try {
            java.nio.file.Files.write(
                    java.nio.file.Path.of("/tmp/asr-" + kindAndUtt + "-" + System.currentTimeMillis() + ".pcm"),
                    pcm);
        } catch (java.io.IOException ignored) {
            // 诊断辅助：落盘失败不影响主流程
        }
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
            default -> {
                // 防御：未知 kind（含旧的 audio）→ 文本化
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

    /** 兜底收敛：记一条决策事件（reason 区分 ASR / 仲裁失败），返回兜底话术结果。 */
    private SegmentResult fallback(SessionContext ctx, String utteranceId, String reason) {
        sink.log(new DecisionEntry(ARBITER_CLOUD, ROUTE_CLOUD, reason, utteranceId, System.currentTimeMillis()));
        return new SegmentResult(null, FALLBACK_TEXT, null, null); // 兜底无识别文本
    }
}
