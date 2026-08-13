package com.autovoice.server.app;

import static org.junit.jupiter.api.Assertions.*;

import com.autovoice.server.app.AppConfig.AutovoiceProperties.Gateway;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Gateway record：auth-devices 以 JSON 字符串接收、{@code authDevicesMap()} 解析为
 * {deviceId: token} 表（部署实测 Boot 无 String→Map 转换器，Boot 3.3.4 env 注入 JSON
 * map 报 ConverterNotFoundException，故 record 内解析）。
 */
class AppConfigGatewayTest {

    @Test
    void parsesValidJsonDeviceTable() {
        Gateway g = new Gateway(true, "{\"demo-1\":\"tok1\",\"demo-2\":\"tok2\"}", 32);
        assertEquals(Map.of("demo-1", "tok1", "demo-2", "tok2"), g.authDevicesMap());
    }

    @Test
    void blankOrNullJsonMeansEmptyTable() {
        assertTrue(new Gateway(true, null, 32).authDevicesMap().isEmpty());
        assertTrue(new Gateway(true, "", 32).authDevicesMap().isEmpty());
        assertTrue(new Gateway(true, "   ", 32).authDevicesMap().isEmpty());
    }

    @Test
    void malformedJsonFailsFast() {
        assertThrows(IllegalArgumentException.class,
                () -> new Gateway(true, "{not-json", 32).authDevicesMap());
    }

    @Test
    void maxConnectionsClamped() {
        assertEquals(32, new Gateway(false, "{}", 0).maxConnections());
        assertEquals(4, new Gateway(false, "{}", 4).maxConnections());
    }
}
