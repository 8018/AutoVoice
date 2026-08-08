package com.autovoice.server.gateway;

import com.autovoice.server.arbitration.DecisionSink;
import com.autovoice.server.arbitration.RaceArbiter;
import com.autovoice.server.contracts.AsrException;
import com.autovoice.server.contracts.AsrProvider;
import com.autovoice.server.contracts.DecisionEntry;
import com.autovoice.server.contracts.Intent;
import com.autovoice.server.contracts.LlmProvider;
import com.autovoice.server.contracts.NluProvider;
import com.autovoice.server.contracts.Reply;
import com.autovoice.server.contracts.SessionContext;
import com.autovoice.server.contracts.TtsProvider;

/**
 * 云端语音段处理流水线（spec §5.2，cloud 链路）：
 * {@code ASR → RaceArbiter(NLU ∥ LLM) → TTS}。
 *
 * <p>接线（按 RaceArbiter 真实 API）：{@code arbiter.decide(text, nlu, llm, ctx).join()}——
 * arbiter 恒返回回复（nlu 非拒识 → action；拒识/超时 → LLM 回复；safety → 兜底文本），
 * 决策日志由 arbiter 经注入的 {@link DecisionSink} 写出。文本抽取按回复 kind：
 * text → {@code reply.text()}；action → {@code reply.speakText()} + intent；
 * audio（demo 不出现）→ data 直通、不 TTS。</p>
 *
 * <p>降级路径（shared/protocol.md §7.2 / §4.4）：</p>
 * <ul>
 *   <li>ASR 失败（抛 {@link AsrException} 或返回空白文本）→ 兜底话术
 *       {@link #FALLBACK_TEXT}，不合成音频，经 sink 记一条决策事件（reason
 *       {@code asr_failed_fallback}）；</li>
 *   <li>arbiter 调用异常 → 同样走兜底话术（reason {@code arbitration_failed_fallback}）；</li>
 *   <li>TTS 合成失败 → 降级为屏幕显示文本：{@code wavAudio=null} 而 {@code speakText} 保留
 *       （网关据此下行 kind=text，见协议修订）。</li>
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

    private final AsrProvider asr;
    private final RaceArbiter arbiter;
    private final NluProvider nlu;
    private final LlmProvider llm;
    private final TtsProvider tts;
    private final DecisionSink sink;

    public SegmentPipeline(AsrProvider asr, RaceArbiter arbiter, NluProvider nlu,
                           LlmProvider llm, TtsProvider tts, DecisionSink sink) {
        this.asr = asr;
        this.arbiter = arbiter;
        this.nlu = nlu;
        this.llm = llm;
        this.tts = tts;
        this.sink = sink;
    }

    /** 一段录音的处理结果：wavAudio 为 null 时网关下行降级 kind=text（仅 speakText）。 */
    public record SegmentResult(byte[] wavAudio, String speakText, Intent intent) {
    }

    public SegmentResult handleSegment(byte[] pcm, SessionContext ctx, String utteranceId) {
        String text = transcribe(pcm, ctx, utteranceId);
        if (text == null) {
            return new SegmentResult(null, FALLBACK_TEXT, null); // ASR 失败兜底：不合成音频
        }
        Reply reply = arbitrate(text, ctx, utteranceId);
        if (reply == null) {
            return new SegmentResult(null, FALLBACK_TEXT, null);
        }
        String speakText;
        Intent intent = null;
        switch (reply.kind()) {
            case "text" -> speakText = reply.text(); // demo 约定 LLM 恒返 text
            case "action" -> {
                speakText = reply.speakText();
                intent = reply.intent();
            }
            case "audio" -> {
                // demo 不出现（LLM/TTS 恒产 wav）：data 直通、speakText=null，不再 TTS
                return new SegmentResult(reply.data(), null, null);
            }
            default -> speakText = reply.speakText() != null ? reply.speakText() : reply.text();
        }
        if (speakText == null || speakText.isBlank()) {
            // 防御：仲裁结果无可播报文本 → 兜底话术
            return fallback(ctx, utteranceId, REASON_ARBITRATION_FAILED);
        }
        return synthesize(speakText, ctx, intent);
    }

    /** ASR：失败或空白识别结果一律走兜底话术。返回 null 表示已兜底收敛。 */
    private String transcribe(byte[] pcm, SessionContext ctx, String utteranceId) {
        try {
            String text = asr.transcribe(pcm, ctx);
            if (text == null || text.isBlank()) {
                fallback(ctx, utteranceId, REASON_ASR_FAILED);
                return null;
            }
            return text;
        } catch (Exception e) {
            fallback(ctx, utteranceId, REASON_ASR_FAILED);
            return null;
        }
    }

    /** 仲裁：decide 恒返回回复；仅当 decide 本身同步抛异常时兜底。返回 null 表示已兜底收敛。 */
    private Reply arbitrate(String text, SessionContext ctx, String utteranceId) {
        try {
            return arbiter.decide(text, nlu, llm, ctx).join();
        } catch (Exception e) {
            fallback(ctx, utteranceId, REASON_ARBITRATION_FAILED);
            return null;
        }
    }

    /** TTS：合成失败（或未产出音频）→ 降级为屏幕显示文本，wavAudio=null 而 speakText 保留。 */
    private SegmentResult synthesize(String speakText, SessionContext ctx, Intent intent) {
        try {
            Reply audio = tts.synthesize(speakText, ctx);
            if (audio == null || !"audio".equals(audio.kind()) || audio.data() == null) {
                return new SegmentResult(null, speakText, intent);
            }
            return new SegmentResult(audio.data(), speakText, intent);
        } catch (Exception e) {
            return new SegmentResult(null, speakText, intent); // TTS 失败 → 降级文本
        }
    }

    /** 兜底收敛：记一条决策事件（reason 区分 ASR / 仲裁失败），返回兜底话术结果。 */
    private SegmentResult fallback(SessionContext ctx, String utteranceId, String reason) {
        sink.log(new DecisionEntry(ARBITER_CLOUD, ROUTE_CLOUD, reason, utteranceId, System.currentTimeMillis()));
        return new SegmentResult(null, FALLBACK_TEXT, null);
    }
}
