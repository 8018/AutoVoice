package com.autovoice.server.skillmcp;

import java.util.concurrent.atomic.AtomicReference;

/** S2S 闲聊域独立 system prompt 快照；不与业务 LLM 的 SystemPromptStore 共享。 */
public final class ChatSystemPromptStore {

    private final AtomicReference<String> ref = new AtomicReference<>();

    public void set(String value) {
        if (value != null) ref.set(value);
    }

    public String get() {
        return ref.get();
    }
}
