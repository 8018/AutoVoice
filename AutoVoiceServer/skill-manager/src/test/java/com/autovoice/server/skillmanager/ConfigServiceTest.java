package com.autovoice.server.skillmanager;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

class ConfigServiceTest {

    /** 内存 fake store：只实现本任务用到的两个方法。 */
    private static final class FakeStore extends SqliteSkillStore {
        final Map<String, String> map = new HashMap<>();
        FakeStore() { super("/nonexistent.db"); }
        @Override public Optional<String> getSetting(String key) { return Optional.ofNullable(map.get(key)); }
        @Override public void setSetting(String key, String value) { map.put(key, value); }
    }

    private static final class FakeNotifier implements SkillWebhookNotifier {
        final List<String> calls = new ArrayList<>();
        @Override public void notifySkillChanged(String skillId) { calls.add(skillId); }
    }

    @Test
    void getReturnsEmptyStringWhenUnset() {
        assertEquals("", new ConfigService(new FakeStore(), new FakeNotifier()).getSystemPrompt());
    }

    @Test
    void setStoresAndNotifiesWebhook() {
        FakeStore store = new FakeStore();
        FakeNotifier notifier = new FakeNotifier();
        ConfigService svc = new ConfigService(store, notifier);
        svc.setSystemPrompt("你是助手");
        assertEquals("你是助手", svc.getSystemPrompt());
        assertEquals(List.of("system-prompt"), notifier.calls);
    }

    @Test
    void chatPromptUsesIndependentKeyAndNotification() {
        FakeStore store = new FakeStore();
        FakeNotifier notifier = new FakeNotifier();
        ConfigService svc = new ConfigService(store, notifier);
        svc.setSystemPrompt("业务");
        svc.setChatSystemPrompt("闲聊");
        assertEquals("业务", svc.getSystemPrompt());
        assertEquals("闲聊", svc.getChatSystemPrompt());
        assertEquals(List.of("system-prompt", "chat-system-prompt"), notifier.calls);
    }

    @Test
    void setEmptyStoresEmptyAndStillNotifies() {
        FakeStore store = new FakeStore();
        FakeNotifier notifier = new FakeNotifier();
        ConfigService svc = new ConfigService(store, notifier);
        svc.setSystemPrompt("");
        assertEquals("", svc.getSystemPrompt());
        assertEquals(1, notifier.calls.size());
    }
}
