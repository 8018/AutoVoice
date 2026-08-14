package com.autovoice.server.skillmanager;

/** skill 变更通知（写操作后触发；网关刷新）。Task 8 提供 HTTP 实现，测试注入 lambda。 */
public interface SkillWebhookNotifier {
    void notifySkillChanged(String skillId);
}
