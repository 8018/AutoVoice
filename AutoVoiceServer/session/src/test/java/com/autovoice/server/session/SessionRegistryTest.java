package com.autovoice.server.session;

import com.autovoice.server.contracts.SessionContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionRegistryTest {

    private final SessionRegistry registry = new SessionRegistry();

    @Test
    void createMakesNewSessionWithGeneratedId() {
        SessionContext ctx = registry.create("zh-CN");
        assertNotNull(ctx);
        assertNotNull(ctx.sessionId());
        assertFalse(ctx.sessionId().isBlank());
        assertEquals("zh-CN", ctx.language());
        assertTrue(ctx.attrs().isEmpty());
        // 两次 create 生成不同的 sessionId
        SessionContext other = registry.create("en-US");
        assertNotEquals(ctx.sessionId(), other.sessionId());
        assertEquals("en-US", other.language());
        assertEquals(2, registry.size());
    }

    @Test
    void getReturnsSameInstanceAndNullForUnknown() {
        SessionContext ctx = registry.create("zh-CN");
        assertSame(ctx, registry.get(ctx.sessionId()));
        assertNull(registry.get("no-such-session"));
    }

    @Test
    void removeDeletesSessionAndIsIdempotent() {
        SessionContext ctx = registry.create("zh-CN");
        registry.remove(ctx.sessionId());
        assertNull(registry.get(ctx.sessionId()));
        assertEquals(0, registry.size());
        // 重复 remove 是幂等 no-op
        registry.remove(ctx.sessionId());
    }

    @Test
    void capacityEvictsOldestInserted() {
        SessionContext first = registry.create("zh-CN");
        SessionContext second = registry.create("zh-CN");
        SessionContext third = registry.create("zh-CN");
        SessionContext fourth = null;
        SessionContext last = null;
        for (int i = 3; i < SessionRegistry.MAX_CAPACITY + 2; i++) { // 共插入 1002 个
            if (i == 3) {
                fourth = registry.create("zh-CN");
            } else {
                last = registry.create("zh-CN");
            }
        }
        assertEquals(SessionRegistry.MAX_CAPACITY, registry.size());
        assertNull(registry.get(first.sessionId())); // 第 1 个被淘汰
        assertNull(registry.get(second.sessionId())); // 第 2 个被淘汰
        assertNotNull(registry.get(third.sessionId())); // 第 3 个仍在
        assertNotNull(registry.get(last.sessionId())); // 最新的仍在

        // remove 须同步清理插入序跟踪：移除 third 后再插入两个新会话，应淘汰当前的"最旧"（第 4 个），
        // 而不是已移除的 third 残留占位
        registry.remove(third.sessionId());
        assertEquals(SessionRegistry.MAX_CAPACITY - 1, registry.size());
        registry.create("zh-CN"); // 回到 1000 个，不触发淘汰
        SessionContext newest = registry.create("zh-CN"); // 1001 个，触发淘汰
        assertEquals(SessionRegistry.MAX_CAPACITY, registry.size());
        assertNull(registry.get(fourth.sessionId())); // 当前最旧（第 4 个）被淘汰
        assertNotNull(registry.get(newest.sessionId())); // 最新的仍在
        assertNull(registry.get(third.sessionId())); // 已 remove 的不会回来
    }
}
