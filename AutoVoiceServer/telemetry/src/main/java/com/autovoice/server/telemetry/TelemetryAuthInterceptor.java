package com.autovoice.server.telemetry;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 可选 telemetry 边界鉴权：配置 token 后，所有 API 请求必须携带同值请求头。 */
final class TelemetryAuthInterceptor implements HandlerInterceptor {

    static final String TOKEN_HEADER = "X-Telemetry-Token";
    private final String expected;

    TelemetryAuthInterceptor(String expected) {
        this.expected = expected == null ? "" : expected;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (expected.isBlank()) {
            return true; // 本地/demo 兼容；生产通过 AUTOVOICE_TELEMETRY_ACCESS_TOKEN 开启
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
}
