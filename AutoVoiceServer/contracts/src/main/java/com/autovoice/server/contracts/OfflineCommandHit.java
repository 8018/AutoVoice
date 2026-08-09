package com.autovoice.server.contracts;

/**
 * 离线命令词命中：识别原文 + 规则 NLU 产出的规范化意图。
 *
 * <p>仅在离线链真正命中（识别到文本且意图非 unknown）时产出，仲裁器以此为准胜出。</p>
 *
 * @param text   识别原文（GBK 解码、trim 后）
 * @param intent 规则 NLU 映射的意图（domain/intent/slots）
 */
public record OfflineCommandHit(String text, Intent intent) {
}
