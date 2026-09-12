package com.autovoice.server.app.health;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * D12a 健康探测端点(不访问付费依赖、不触网):
 *
 * <ul>
 *   <li>{@code GET /health/live}:进程存活——只要服务在运行即 200,供进程级重启判定;</li>
 *   <li>{@code GET /health/ready}:业务就绪——关键组件全部 READY 且未排空才 200,
 *       供负载均衡/部署脚本判定是否可接管流量;</li>
 *   <li>{@code POST /health/drain}:进入排空(停止接入、等待在途,有期限),供发布切换使用。</li>
 * </ul>
 *
 * <p>就绪判定只读内存状态,不调用模型/MCP/TTS 等外部依赖,因此可以被频繁探测。</p>
 */
@RestController
@RequestMapping("/health")
public class HealthController {

    private final ServiceReadiness readiness;

    public HealthController(ServiceReadiness readiness) {
        this.readiness = readiness;
    }

    @GetMapping("/live")
    public ResponseEntity<Map<String, Object>> live() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        return ResponseEntity.ok(body);
    }

    @GetMapping("/ready")
    public ResponseEntity<Map<String, Object>> ready() {
        boolean ready = readiness.isReady();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", ready ? "UP" : "DOWN");
        body.put("draining", readiness.isDraining());
        body.put("inFlight", readiness.inFlight());
        body.put("components", readiness.snapshot());
        return ready ? ResponseEntity.ok(body) : ResponseEntity.status(503).body(body);
    }

    @PostMapping("/drain")
    public ResponseEntity<Map<String, Object>> drain() {
        readiness.beginDraining();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("draining", true);
        body.put("inFlight", readiness.inFlight());
        return ResponseEntity.ok(body);
    }
}
