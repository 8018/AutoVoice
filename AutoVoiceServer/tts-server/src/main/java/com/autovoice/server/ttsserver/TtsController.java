package com.autovoice.server.ttsserver;

import com.autovoice.server.contracts.Reply;
import com.autovoice.server.contracts.SessionContext;
import com.autovoice.server.contracts.TtsProvider;

import java.util.Base64;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * TTS 服务内部端点（M4）：{@code POST /tts}，body {@code {text, sessionId}} →
 * {@code {mime, dataBase64}}。仅供接入网关的 RemoteTtsProvider 调用（内部协议进 runbook，
 * 不进对外设备契约）。缺 text → 400；合成失败 → 500（网关按 TTS_FAILED 语义降级文本回复）。
 */
@RestController
public class TtsController {

    /** 请求体；sessionId 可空（网关总是带，供日志/未来统计）。 */
    public record TtsRequest(String text, String sessionId) {
    }

    public record TtsResponse(String mime, String dataBase64) {
    }

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(TtsController.class);

    private final TtsProvider tts;

    public TtsController(TtsProvider tts) {
        this.tts = tts;
    }

    @PostMapping("/tts")
    public TtsResponse synthesize(@RequestBody TtsRequest request) {
        if (request == null || request.text() == null || request.text().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "text is required");
        }
        try {
            Reply reply = tts.synthesize(request.text(),
                    new SessionContext(request.sessionId() == null ? "" : request.sessionId(), null, null));
            if (reply == null || reply.data() == null || reply.data().length == 0) {
                LOG.warn("TTS synthesize produced no audio: \"{}\"", request.text());
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "synthesize produced no audio");
            }
            return new TtsResponse(reply.mime() == null ? "audio/wav" : reply.mime(),
                    Base64.getEncoder().encodeToString(reply.data()));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            // 合成失败（网络/服务端拒绝）：网关按 TTS_FAILED 语义降级文本回复
            LOG.warn("TTS synthesize failed: {}", String.valueOf(e.getMessage()));
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "tts synthesize failed: " + e.getMessage());
        }
    }
}
