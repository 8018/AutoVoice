package com.autovoice.server.skillmanager;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 平台鉴权：/api/skills/** 与 /api/admin/**（除 login/logout）要求
 * 服务端会话 cookie（D03b，由 AdminSessionStore 校验与撤销）
 * 或 X-Skill-Service-Token 二选一通过。
 */
public class AdminAuthInterceptor implements HandlerInterceptor {

    public static final String SERVICE_TOKEN_HEADER = "X-Skill-Service-Token";

    private final AdminSessionStore sessions;
    private final String serviceToken;

    public AdminAuthInterceptor(AdminSessionStore sessions, String serviceToken) {
        this.sessions = sessions;
        this.serviceToken = serviceToken == null ? "" : serviceToken;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (checkServiceToken(request) || checkSessionCookie(request.getCookies())) {
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

    /** 管理会话 cookie 校验：令牌由会话存储服务端判定(存在、未过期),注销即失效。 */
    private boolean checkSessionCookie(Cookie[] cookies) {
        if (cookies == null) {
            return false;
        }
        for (Cookie cookie : cookies) {
            if (AdminController.COOKIE_NAME.equals(cookie.getName())
                    && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                return sessions.validate(cookie.getValue());
            }
        }
        return false;
    }
}
