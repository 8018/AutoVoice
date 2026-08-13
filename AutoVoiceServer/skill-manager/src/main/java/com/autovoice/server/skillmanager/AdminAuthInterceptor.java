package com.autovoice.server.skillmanager;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 平台鉴权：/api/skills/** 与 /api/admin/**（除 login/logout）要求
 * 管理端 cookie 或 X-Skill-Service-Token 二选一通过。
 */
public class AdminAuthInterceptor implements HandlerInterceptor {

    public static final String SERVICE_TOKEN_HEADER = "X-Skill-Service-Token";

    private final String adminToken;
    private final String serviceToken;

    public AdminAuthInterceptor(String adminToken, String serviceToken) {
        this.adminToken = adminToken == null ? "" : adminToken;
        this.serviceToken = serviceToken == null ? "" : serviceToken;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (checkServiceToken(request) || checkCookie(request)) {
            return true;
        }
        response.setStatus(401);
        response.setContentType("application/json; charset=utf-8");
        response.getWriter().write("{\"error\":\"unauthorized\"}");
        return false;
    }

    private boolean checkServiceToken(HttpServletRequest request) {
        String given = request.getHeader(SERVICE_TOKEN_HEADER);
        // 先拒空：空 header 绝不等于任何配置 token（防 token 侧为空的静默开门）
        return given != null && !given.isEmpty() && MessageDigest.isEqual(
                given.getBytes(StandardCharsets.UTF_8), serviceToken.getBytes(StandardCharsets.UTF_8));
    }

    private boolean checkCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return false;
        }
        String expected = AdminController.cookieValue(adminToken);
        for (Cookie c : cookies) {
            if (AdminController.COOKIE_NAME.equals(c.getName()) && c.getValue() != null
                    && MessageDigest.isEqual(c.getValue().getBytes(StandardCharsets.UTF_8),
                                              expected.getBytes(StandardCharsets.UTF_8))) {
                return true;
            }
        }
        return false;
    }
}
