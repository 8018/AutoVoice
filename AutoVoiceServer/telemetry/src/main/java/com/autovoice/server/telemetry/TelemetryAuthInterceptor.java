package com.autovoice.server.telemetry;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * telemetry 边界鉴权(D03a 读写分权):
 * <ul>
 *   <li>上传(POST:round/events/audio)要求设备接入令牌(access-token);</li>
 *   <li>查询(GET:rounds/detail/stream/audio)要求管理令牌(admin-token);
 *       admin-token 未配置时回落到 access-token(本地/demo 兼容);</li>
 *   <li>两者都未配置 → 放行(本地裸连,生产由 ProductionConfigGuard fail-closed 约束)。</li>
 * </ul>
 * 上传凭据不能查询记录;管理令牌不能用于设备上传。
 */
final class TelemetryAuthInterceptor implements HandlerInterceptor {

    static final String TOKEN_HEADER = "X-Telemetry-Token";

    private final String accessToken;
    private final String adminToken;

    /** 兼容构造:单一令牌(历史上传/查询共用),行为等同 admin 回落 access。 */
    TelemetryAuthInterceptor(String accessToken) {
        this(accessToken, "");
    }

    TelemetryAuthInterceptor(String accessToken, String adminToken) {
        this.accessToken = accessToken == null ? "" : accessToken;
        this.adminToken = adminToken == null ? "" : adminToken;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String expected = expectedToken(request);
        if (expected.isBlank()) {
            return true; // 本地/demo 兼容;生产由 ProductionConfigGuard 约束
        }
        String given = request.getHeader(TOKEN_HEADER);
        if (given != null && !given.isBlank() && MessageDigest.isEqual(
                given.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json; charset=utf-8");
        response.getWriter().write("{\"error\":\"unauthorized\"}");
        return false;
    }

    private String expectedToken(HttpServletRequest request) {
        if ("POST".equalsIgnoreCase(request.getMethod())) {
            return accessToken;
        }
        return adminToken.isBlank() ? accessToken : adminToken;
    }
}
