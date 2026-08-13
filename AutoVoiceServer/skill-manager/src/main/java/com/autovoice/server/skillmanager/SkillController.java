package com.autovoice.server.skillmanager;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * skill 管理 + 网关拉取 API。鉴权由 AdminAuthInterceptor 统一做；
 * 视图区分：?enabled=true（service token）→ 网关视图明文；否则管理端视图掩码。
 */
@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private final SkillService service;
    private final SqliteSkillStore store;
    private final McpDiscoveryClient discovery;

    public SkillController(SkillService service, SqliteSkillStore store, McpDiscoveryClient discovery) {
        this.service = service;
        this.store = store;
        this.discovery = discovery;
    }

    @GetMapping
    public List<SkillResponse> list(@RequestParam(value = "enabled", required = false) Boolean enabled,
                                    HttpServletRequest request) {
        boolean gatewayView = Boolean.TRUE.equals(enabled);
        return service.list(gatewayView);
    }

    @GetMapping("/{id}")
    public ResponseEntity<SkillResponse> get(@PathVariable String id) {
        SkillResponse r = service.get(id, false);
        return r == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(r);
    }

    @PostMapping
    public ResponseEntity<SkillResponse> create(@RequestBody SkillRequest req) {
        if (req.id() == null || req.id().isBlank() || !req.id().matches("[a-zA-Z0-9._-]+")) {
            return ResponseEntity.badRequest().build();
        }
        if (service.exists(req.id())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        return ResponseEntity.ok(service.create(req));
    }

    @PutMapping("/{id}")
    public ResponseEntity<SkillResponse> update(@PathVariable String id, @RequestBody SkillRequest req) {
        if (!service.exists(id)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(service.update(id, req));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        if (!service.exists(id)) {
            return ResponseEntity.notFound().build();
        }
        service.delete(id);
        return ResponseEntity.ok().build();
    }

    @PatchMapping("/{id}/enabled")
    public ResponseEntity<SkillResponse> setEnabled(@PathVariable String id,
                                                    @RequestBody EnableRequest req) {
        if (!service.exists(id)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(service.setEnabled(id, req.enabled()));
    }

    /** 发现工具（用保存的配置；body 可选覆盖 mcpUrl/authHeader/authValue），不落库。 */
    @PostMapping("/{id}/discover")
    public ResponseEntity<List<ToolInfo>> discover(@PathVariable String id,
                                                   @RequestBody(required = false) DiscoverOverride body) {
        SkillRecord r = store.findById(id);
        if (r == null) {
            return ResponseEntity.notFound().build();
        }
        String mcpUrl = body != null && body.mcpUrl() != null ? body.mcpUrl() : r.mcpUrl();
        String authHeader = body != null && body.authHeader() != null ? body.authHeader() : r.authHeader();
        String authValue = body != null && body.authValue() != null ? body.authValue() : r.authValue();
        try {
            return ResponseEntity.ok(discovery.discover(mcpUrl, authHeader, authValue));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(null);
        }
    }

    public record EnableRequest(boolean enabled) {}

    public record DiscoverOverride(String mcpUrl, String authHeader, String authValue) {}
}
