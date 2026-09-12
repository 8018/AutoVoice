package com.autovoice.server.skillmanager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.autovoice.server.contracts.testing.TestClock;
import org.junit.jupiter.api.Test;

/** D03b 管理会话存储:随机会话令牌、服务端过期、注销撤销。 */
class AdminSessionStoreTest {

    private static final long TTL_MS = 3_600_000L;

    private final TestClock clock = new TestClock(1_000_000L);
    private final AdminSessionStore store = new AdminSessionStore(clock, TTL_MS);

    @Test
    void createIssuesUniqueRandomTokens() {
        String first = store.create();
        String second = store.create();
        assertFalse(first.isBlank());
        assertFalse(second.isBlank());
        assertFalse(first.equals(second), "会话令牌必须随机且互不相同");
        assertEquals(2, store.size());
    }

    @Test
    void validTokenPassesAndSlidesExpiry() {
        String token = store.create();
        clock.advance(TTL_MS - 1);
        assertTrue(store.validate(token), "未过期令牌应通过");
        clock.advance(2);
        assertTrue(store.validate(token), "续期后仍未过期");
        clock.advance(TTL_MS + 1);
        assertFalse(store.validate(token), "超过续期 TTL 后应拒绝");
    }

    @Test
    void expiredSessionIsEvictedOnValidate() {
        String token = store.create();
        clock.advance(TTL_MS + 1);
        assertFalse(store.validate(token));
        assertEquals(0, store.size(), "过期会话应被惰性移除");
    }

    @Test
    void revokedTokenFailsAndIsRemoved() {
        String token = store.create();
        store.revoke(token);
        assertFalse(store.validate(token), "已注销令牌不能重放");
        assertEquals(0, store.size());
    }

    @Test
    void unknownTokenFails() {
        assertFalse(store.validate("not-a-token"));
    }
}
