package com.autovoice.server.app.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.autovoice.server.contracts.testing.TestClock;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * D12a 健康端点契约(直接测控制器,不启 Spring 上下文):
 * 存活恒 200;就绪反映关键组件与排空状态且不访问付费依赖。
 */
class HealthControllerTest {

    private final TestClock clock = new TestClock(0);
    private final ServiceReadiness readiness = new ServiceReadiness(clock, 5_000);
    private final HealthController controller = new HealthController(readiness);

    @Test
    void liveEndpointAlwaysUp() {
        ResponseEntity<Map<String, Object>> response = controller.live();
        assertEquals(200, response.getStatusCode().value());
        assertEquals("UP", response.getBody().get("status"));
    }

    @Test
    void readyEndpointUpWithoutCriticalComponents() {
        ResponseEntity<Map<String, Object>> response = controller.ready();
        assertEquals(200, response.getStatusCode().value());
        assertEquals("UP", response.getBody().get("status"));
        assertEquals(false, response.getBody().get("draining"));
    }

    @Test
    void readyEndpointUnavailableWhileDraining() {
        readiness.beginDraining();
        ResponseEntity<Map<String, Object>> response = controller.ready();
        assertEquals(503, response.getStatusCode().value());
        assertEquals("DOWN", response.getBody().get("status"));
        assertEquals(true, response.getBody().get("draining"));
    }

    @Test
    void readyEndpointDownWhenCriticalComponentFailed() {
        readiness.registerCritical("offline-engine");
        readiness.markFailed("offline-engine", "init failed");
        assertEquals(503, controller.ready().getStatusCode().value());
    }

    @Test
    void drainEndpointStopsAdmission() {
        ResponseEntity<Map<String, Object>> response = controller.drain();
        assertEquals(200, response.getStatusCode().value());
        assertEquals(true, response.getBody().get("draining"));
        assertTrue(readiness.isDraining());
    }
}
