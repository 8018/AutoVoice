package com.autovoice.server.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.autovoice.server.contracts.SessionContext;
import com.autovoice.server.contracts.testing.TestClock;
import com.autovoice.server.session.SessionRegistry.ResumeResult;
import org.junit.jupiter.api.Test;

/** D02a 会话所有权与恢复凭据:跨设备不可恢复、错误/过期凭据拒绝、恢复凭据独立于 sessionId。 */
class SessionRecoveryTest {

    private static final long TTL_MS = 60_000L;

    private final TestClock clock = new TestClock(1_000_000L);
    private final SessionRegistry registry = new SessionRegistry(clock, TTL_MS);

    @Test
    void createWithOwnerBindsOwnerAndIssuesResumeToken() {
        SessionContext ctx = registry.create("zh-CN", "device-a");
        assertEquals("device-a", ctx.owner());
        assertFalse(ctx.resumeToken().isBlank());
        assertEquals(clock.now(), ctx.createdAtMs());
        assertEquals(clock.now() + TTL_MS, ctx.expiresAtMs());
    }

    @Test
    void ownerWithValidTokenResumesSession() {
        SessionContext ctx = registry.create("zh-CN", "device-a");
        clock.advance(5_000L);
        assertEquals(ResumeResult.OK,
            registry.resume(ctx.sessionId(), "device-a", ctx.resumeToken()));
        SessionContext latest = registry.get(ctx.sessionId());
        assertEquals(clock.now(), latest.lastActiveAtMs());
        assertEquals(clock.now() + TTL_MS, latest.expiresAtMs());
    }

    @Test
    void otherOwnerCannotResumeSession() {
        SessionContext ctx = registry.create("zh-CN", "device-a");
        assertEquals(ResumeResult.OWNER_MISMATCH,
            registry.resume(ctx.sessionId(), "device-b", ctx.resumeToken()));
        // 会话仍然存在且未被改动
        assertEquals(ctx.sessionId(), registry.get(ctx.sessionId()).sessionId());
    }

    @Test
    void wrongRecoveryTokenIsRejected() {
        SessionContext ctx = registry.create("zh-CN", "device-a");
        assertEquals(ResumeResult.BAD_CREDENTIAL,
            registry.resume(ctx.sessionId(), "device-a", "not-the-token"));
    }

    @Test
    void expiredSessionIsRejectedAndRemoved() {
        SessionContext ctx = registry.create("zh-CN", "device-a");
        clock.advance(TTL_MS + 1);
        assertEquals(ResumeResult.EXPIRED,
            registry.resume(ctx.sessionId(), "device-a", ctx.resumeToken()));
        assertNull(registry.get(ctx.sessionId()));
        assertEquals(0, registry.size());
    }

    @Test
    void unknownSessionIsNotFound() {
        assertEquals(ResumeResult.NOT_FOUND,
            registry.resume("no-such-session", "device-a", "token"));
    }

    @Test
    void legacyCreateWithoutOwnerKeepsDemoSemantics() {
        SessionContext ctx = registry.create("zh-CN");
        assertEquals("", ctx.owner());
        assertNotNull(ctx.resumeToken()); // 凭据仍然签发,生产校验不因空 owner 放行
    }

    @Test
    void demoPathResumeWithoutOwnershipStillReadsSession() {
        SessionContext ctx = registry.create("zh-CN");
        assertTrue(ctx.sessionId().length() > 0);
        assertEquals(ctx.sessionId(), registry.get(ctx.sessionId()).sessionId());
    }
}
