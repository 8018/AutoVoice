package com.autovoice.server.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** D10a 每设备连接配额:单设备不得占满接入名额;释放后可复用;未认证主体独立计数。 */
class ConnectionQuotaTest {

    @Test
    void enforcesPerSubjectLimit() {
        ConnectionQuota quota = new ConnectionQuota(2);
        assertTrue(quota.tryAcquire("device-a"));
        assertTrue(quota.tryAcquire("device-a"));
        assertFalse(quota.tryAcquire("device-a"), "超过每设备配额应拒绝");
        assertEquals(2, quota.activeFor("device-a"));
    }

    @Test
    void otherSubjectsUnaffected() {
        ConnectionQuota quota = new ConnectionQuota(1);
        assertTrue(quota.tryAcquire("device-a"));
        assertFalse(quota.tryAcquire("device-a"));
        assertTrue(quota.tryAcquire("device-b"), "一个设备占满不影响其他设备");
        assertEquals(1, quota.activeFor("device-b"));
    }

    @Test
    void releaseRestoresCapacity() {
        ConnectionQuota quota = new ConnectionQuota(1);
        assertTrue(quota.tryAcquire("device-a"));
        quota.release("device-a");
        assertEquals(0, quota.activeFor("device-a"));
        assertTrue(quota.tryAcquire("device-a"), "释放后名额可复用");
    }

    @Test
    void anonymousSubjectCountedSeparately() {
        ConnectionQuota quota = new ConnectionQuota(1);
        assertTrue(quota.tryAcquire(""));
        assertTrue(quota.tryAcquire("device-a"), "未认证连接与已认证设备分开计数");
    }

    @Test
    void releaseWithoutAcquireDoesNotGoNegative() {
        ConnectionQuota quota = new ConnectionQuota(1);
        quota.release("device-a");
        quota.release("device-a");
        assertEquals(0, quota.activeFor("device-a"));
        assertTrue(quota.tryAcquire("device-a"));
    }
}
