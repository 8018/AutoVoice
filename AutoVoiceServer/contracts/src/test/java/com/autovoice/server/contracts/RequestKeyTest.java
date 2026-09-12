package com.autovoice.server.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** D06a 复合请求标识契约。 */
class RequestKeyTest {

    @Test
    void normalizesNullComponentsToEmpty() {
        RequestKey key = RequestKey.of(null, null, null, null);
        assertEquals("", key.subject());
        assertEquals("", key.session());
        assertEquals("", key.turn());
        assertEquals("", key.request());
    }

    @Test
    void distinguishesSameTurnAcrossDevices() {
        RequestKey deviceA = RequestKey.of("device-a", "sess-1", "turn-7", "");
        RequestKey deviceB = RequestKey.of("device-b", "sess-2", "turn-7", "");
        assertEquals("turn-7", deviceA.turn());
        assertEquals(false, deviceA.equals(deviceB), "同名轮次跨设备必须可区分");
    }
}
