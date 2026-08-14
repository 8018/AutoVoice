package com.autovoice.server.skillmanager;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
    public ResponseEntity<?> putSystemPrompt(HttpServletRequest request,
                                             @RequestBody PromptRequest body) {
        // 写操作仅管理端：service token（内部网关 token）无写权
        if (!hasAdminCookie(request)) {
            return ResponseEntity.status(401).body("{\"error\":\"unauthorized\"}");
        }
        String value = body.value() == null ? "" : body.value();
        service.setSystemPrompt(value);
        return ResponseEntity.ok(Map.of("value", service.getSystemPrompt()));
    }

    public record PromptRequest(String value) {}

    private boolean hasAdminCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return false;
        }
        String expected = AdminController.cookieValue(adminToken);
        for (Cookie c : cookies) {
            if (AdminController.COOKIE_NAME.equals(c.getName()) && c.getValue() != null
                    && !c.getValue().isEmpty()
                    && MessageDigest.isEqual(c.getValue().getBytes(StandardCharsets.UTF_8),
                                              expected.getBytes(StandardCharsets.UTF_8))) {
                return true;
            }
        }
        return false;
    }
}
