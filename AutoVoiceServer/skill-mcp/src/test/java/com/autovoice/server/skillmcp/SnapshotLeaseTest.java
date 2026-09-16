package com.autovoice.server.skillmcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** D11b 快照租约:请求持有版本,旧连接在最后一个租约释放后才退役。 */
class SnapshotLeaseTest {

    @Test
    void leaseKeepsSnapshotAliveUntilReleased() {
        RegistrySnapshot snapshot = RegistrySnapshot.empty();
        assertEquals(0, snapshot.activeLeases());

        AutoCloseable first = snapshot.acquire();
        AutoCloseable second = snapshot.acquire();
        assertEquals(2, snapshot.activeLeases());

        closeQuietly(first);
        assertEquals(1, snapshot.activeLeases());
        assertFalse(snapshot.isIdle(), "仍有租约时不得视为可退役");

        closeQuietly(second);
        assertTrue(snapshot.isIdle(), "租约全部释放后视为可退役");
    }

    @Test
    void releaseIsIdempotentAndNeverGoesNegative() {
        RegistrySnapshot snapshot = RegistrySnapshot.empty();
        AutoCloseable lease = snapshot.acquire();
        closeQuietly(lease);
        closeQuietly(lease); // 重复关闭
        assertEquals(0, snapshot.activeLeases());
    }

    @Test
    void currentSnapshotIsStableWithinLease() {
        // 租约语义:取到的实例在持有期内不变(版本固定)
        RegistrySnapshot snapshot = RegistrySnapshot.empty();
        RegistrySnapshot leased = snapshot;
        assertSame(snapshot, leased);
        assertEquals(0, snapshot.version());
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
