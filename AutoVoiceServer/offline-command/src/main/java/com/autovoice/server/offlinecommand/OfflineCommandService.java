package com.autovoice.server.offlinecommand;

import com.autovoice.server.contracts.Intent;
import com.autovoice.server.contracts.OfflineCommandHit;
import com.autovoice.server.contracts.OfflineCommandProvider;
import com.autovoice.server.contracts.SessionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 离线命令链编排：Provider（原生识别）→ RuleNlu（规则映射）→ 命中结果。
 *
 * <p>统一兜底语义：识别空/失败/规则未命中（unknown）一律产出空结果（等同未命中），
 * 绝不把异常传播到话语链路。部署验收日志点：</p>
 * <ul>
 *   <li>{@code Offline ASR ok: "..."} —— 识别到非空文本；</li>
 *   <li>{@code Offline no result} —— 识别空/失败/unknown，离线链未命中。</li>
 * </ul>
 */
public final class OfflineCommandService {

    private static final Logger LOG = LoggerFactory.getLogger(OfflineCommandService.class);

    private final OfflineCommandProvider provider;

    public OfflineCommandService(OfflineCommandProvider provider) {
        this.provider = provider;
    }

    /**
     * 识别 + 规则映射，永不异常完成。结果：命中 → 含 OfflineCommandHit；否则空。
     */
    public CompletableFuture<Optional<OfflineCommandHit>> recognize(byte[] pcm16k, SessionContext ctx) {
        CompletableFuture<Optional<String>> raw;
        try {
            raw = provider.recognize(pcm16k, ctx);
        } catch (Throwable t) {
            // 同步抛异常（罕见）：等同未命中，兜底为空结果
            LOG.warn("Offline ASR failed (sync): {}", String.valueOf(t.getMessage()));
            return CompletableFuture.completedFuture(Optional.empty());
        }
        if (raw == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return raw
                .exceptionally(t -> {
                    LOG.warn("Offline ASR failed: {}", String.valueOf(t.getMessage()));
                    return Optional.empty();
                })
                .thenApply(opt -> opt.flatMap(this::toHit));
    }

    private Optional<OfflineCommandHit> toHit(String text) {
        if (text == null || text.isBlank()) {
            LOG.info("Offline no result");
            return Optional.empty();
        }
        String trimmed = text.trim();
        LOG.info("Offline ASR ok: \"{}\"", trimmed);
        Intent intent = RuleNlu.understand(trimmed);
        if (intent.isUnknown()) {
            LOG.info("Offline no result (unknown intent)");
            return Optional.empty();
        }
        return Optional.of(new OfflineCommandHit(trimmed, intent));
    }
}
