package com.autovoice.server.skillmcp;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SystemPromptStoreTest {

    @Test
    void initialNull() {
        assertNull(new SystemPromptStore().get());
    }

    @Test
    void setThenGet() {
        SystemPromptStore store = new SystemPromptStore();
        store.set("你是助手");
        assertEquals("你是助手", store.get());
    }

    @Test
    void setNullIgnoredKeepsPrevious() {
        SystemPromptStore store = new SystemPromptStore();
        store.set("a");
        store.set(null);
        assertEquals("a", store.get());
    }

    @Test
    void emptyValueAllowed() {
        SystemPromptStore store = new SystemPromptStore();
        store.set("");
        assertEquals("", store.get());
    }
}
