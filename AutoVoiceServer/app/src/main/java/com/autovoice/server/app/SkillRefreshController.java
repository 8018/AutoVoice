package com.autovoice.server.app;

import com.autovoice.server.skillmcp.McpSkillRegistry;
import com.autovoice.server.skillmcp.SkillPlatformClient;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 平台 webhook 接收端：X-Skill-Service-Token 校验后触发 registry 重拉。 */
@RestController
@RequestMapping("/api/internal/skills")
public class SkillRefreshController {

    private final McpSkillRegistry registry;
    private final String serviceToken;

    public SkillRefreshController(McpSkillRegistry registry, String serviceToken) {
        this.registry = registry;
        this.serviceToken = serviceToken == null ? "" : serviceToken;
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(HttpServletRequest request) {
        String given = request.getHeader(SkillPlatformClient.SERVICE_TOKEN_HEADER);
        // 先拒空：空 header 绝不等于任何配置 token（防 token 侧为空的静默开门）
        boolean ok = given != null && !given.isEmpty() && MessageDigest.isEqual(
                given.getBytes(StandardCharsets.UTF_8), serviceToken.getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            return ResponseEntity.status(401).body("{\"error\":\"unauthorized\"}");
        }
        registry.refreshAsync();
        return ResponseEntity.ok("{\"status\":\"ok\"}");
    }
}
