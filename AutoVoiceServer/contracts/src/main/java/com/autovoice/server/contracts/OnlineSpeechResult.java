package com.autovoice.server.contracts;

import java.util.Objects;

/**
 * 在线语音候选的完整结果。
 *
 * <p>Classic 后端返回 ASR 文本和 LLM 语义；Omni 后端直接返回 audio reply，并由并行
 * 旁路 ASR 补充用户原话。旁路识别失败时允许 {@code asrText} 为空。该结果进入云端仲裁器，与
 * {@link OfflineCommandHit} 并发竞争，但不参与端侧仲裁——端侧只接收云端仲裁后的 reply。</p>
 */
public record OnlineSpeechResult(Reply reply, String asrText) {

    public OnlineSpeechResult {
        Objects.requireNonNull(reply, "reply");
        asrText = asrText == null ? "" : asrText;
    }
}
