package com.autovoice.server.contracts;

/**
 * D07a 动作计划:输出准入通过后由网关签发的稳定动作身份。
 * 客户端执行入口凭 actionId 做最终准入与幂等执行。
 */
public record ActionPlan(
        String actionId,
        String sessionId,
        String utteranceId,
        String domain,
        String intent,
        String summary,
        long createdAtMs,
        long expiresAtMs) {

    public ActionPlan {
        actionId = actionId == null ? "" : actionId;
        sessionId = sessionId == null ? "" : sessionId;
        utteranceId = utteranceId == null ? "" : utteranceId;
        domain = domain == null ? "" : domain;
        intent = intent == null ? "" : intent;
        summary = summary == null ? "" : summary;
    }

    /** 兼容构造(历史调用方):按支持窗口推导有效期。 */
    public ActionPlan(String actionId, String sessionId, String utteranceId, String domain,
                      String intent, String summary, long createdAtMs) {
        this(actionId, sessionId, utteranceId, domain, intent, summary, createdAtMs,
                createdAtMs + DEFAULT_SUPPORT_WINDOW_MS);
    }

    /**
     * D14c 幂等支持窗口:**超过该窗口的请求明确拒绝**,不得因账本查不到就当新动作执行
     * (旧请求重放可能重复执行副作用)。窗口独立于审计保留期(审计可保留更久)。
     */
    public static final long DEFAULT_SUPPORT_WINDOW_MS = 24L * 60 * 60 * 1000;

    /** 是否仍在可重放/可执行的支持窗口内。 */
    public boolean withinSupportWindow(long nowMs) {
        return expiresAtMs > 0 && nowMs < expiresAtMs;
    }
}
