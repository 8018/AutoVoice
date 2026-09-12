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
        long createdAtMs) {

    public ActionPlan {
        actionId = actionId == null ? "" : actionId;
        sessionId = sessionId == null ? "" : sessionId;
        utteranceId = utteranceId == null ? "" : utteranceId;
        domain = domain == null ? "" : domain;
        intent = intent == null ? "" : intent;
        summary = summary == null ? "" : summary;
    }
}
