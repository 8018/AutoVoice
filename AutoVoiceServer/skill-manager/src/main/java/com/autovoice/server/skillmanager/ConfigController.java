package com.autovoice.server.skillmanager;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 平台级配置：system prompt。GET 供网关拉取（admin cookie 或 service token）；
 * PUT 仅管理端（admin cookie），保存后经 ConfigService 触发 webhook 推网关刷新。
 */
@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private final ConfigService service;
    private final String adminToken;

    public ConfigController(ConfigService service, SkillProperties props) {
        this.service = service;
        this.adminToken = props.adminToken();
    }

    @GetMapping("/system-prompt")
    public Map<String, String> getSystemPrompt() {
        return Map.of("value", service.getSystemPrompt());
    }

    @PutMapping("/system-prompt")
    public ResponseEntity<Map<String, String>> putSystemPrompt(HttpServletRequest request,
                                                               @RequestBody PromptRequest body) {
        // 写操作仅管理端：service token（内部网关 token）无写权
        if (!hasAdminCookie(request)) {
            return ResponseEntity.status(401).body(Map.of("error", "unauthorized"));
        }
        // 契约是 {"value":"..."}（空串合法=显式恢复默认）；缺失/null 视为笔误或恶意请求，拒绝而非静默清空
        if (body.value() == null) return ResponseEntity.status(400).body(Map.of("error", "missing value field"));
        String value = body.value();
        service.setSystemPrompt(value);
        return ResponseEntity.ok(Map.of("value", service.getSystemPrompt()));
    }

    @GetMapping("/chat-system-prompt")
    public Map<String, String> getChatSystemPrompt() {
        return Map.of("value", service.getChatSystemPrompt());
    }

    @PutMapping("/chat-system-prompt")
    public ResponseEntity<Map<String, String>> putChatSystemPrompt(HttpServletRequest request,
                                                                   @RequestBody PromptRequest body) {
        if (!hasAdminCookie(request)) {
            return ResponseEntity.status(401).body(Map.of("error", "unauthorized"));
        }
        if (body.value() == null) {
            return ResponseEntity.status(400).body(Map.of("error", "missing value field"));
        }
        service.setChatSystemPrompt(body.value());
        return ResponseEntity.ok(Map.of("value", service.getChatSystemPrompt()));
    }

    public record PromptRequest(String value) {}

    private boolean hasAdminCookie(HttpServletRequest request) {
        return AdminAuthInterceptor.matchesAdminCookie(adminToken, request.getCookies());
    }
}
