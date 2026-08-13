package com.autovoice.server.contracts;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 离线命令词识别 Provider SPI（服务端"传统链路"入口）：
 * PCM（S16LE / 16 kHz / 单声道）→ 识别文本（原始语音文本，非规范化意图），异步。
 *
 * <p>实现约定：识别失败（SDK 异常/无结果/引擎未就绪）一律返回 {@code completedFuture(empty)} 或
 * 异常完成——由上层 {@code OfflineCommandService} 统一兜底为"未命中"，绝不中断话语链路。
 * 空结果以 {@link Optional#empty()} 表示；结果文本为原始 GBK 解码后的语音文本。</p>
 */
public interface OfflineCommandProvider {

    CompletableFuture<Optional<String>> recognize(byte[] pcm16k, SessionContext ctx);

    /**
     * 带 utteranceId 的入口（telemetry 贯通）：实现方以 utteranceId 记录 offline_pool 事件。
     * 默认转发到 {@link #recognize(byte[], SessionContext)}（旧实现零改动，仅不产生
     * utteranceId 级事件）。
     */
    default CompletableFuture<Optional<String>> recognize(byte[] pcm16k, SessionContext ctx,
                                                          String utteranceId) {
        return recognize(pcm16k, ctx);
    }
}
