package com.autovoice.server.skillmanager;

/** 平台级配置：当前只有 system prompt；写操作后通知网关刷新（同 skill 变更语义）。 */
public final class ConfigService {

    // 两个命名空间故意不同：存储 key 用下划线（settings 表），webhook skillId 契约是连字符
    // "system-prompt"（测试锁定，网关按此识别），勿"统一命名"改坏契约。
    static final String SYSTEM_PROMPT_KEY = "system_prompt";

    private final SqliteSkillStore store;
    private final SkillWebhookNotifier notifier;

    public ConfigService(SqliteSkillStore store, SkillWebhookNotifier notifier) {
        this.store = store;
        this.notifier = notifier;
    }

    /** 未配置返回空串（网关侧回退内置默认）。 */
    public String getSystemPrompt() {
        return store.getSetting(SYSTEM_PROMPT_KEY).orElse("");
    }

    /** 保存并触发网关刷新（空串合法 = 恢复默认）。 */
    public void setSystemPrompt(String value) {
        store.setSetting(SYSTEM_PROMPT_KEY, value);
        notifier.notifySkillChanged("system-prompt");
    }
}
