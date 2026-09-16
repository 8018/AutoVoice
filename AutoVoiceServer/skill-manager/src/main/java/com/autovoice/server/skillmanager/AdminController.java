package com.autovoice.server.skillmanager;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 管理端口令登录(D03b 服务端随机会话):密码匹配后签发随机会话令牌 cookie;
 * 会话由 {@link AdminSessionStore} 服务端校验与撤销;登录按来源 IP 限流。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    static final String COOKIE_NAME = "skill_admin";

    private final String adminToken;
    private final AdminSessionStore sessions;
    private final LoginRateLimiter limiter;
    private final long sessionTtlMs;
    private final boolean cookieSecure;

    public AdminController(SkillProperties props, AdminSessionStore sessions, LoginRateLimiter limiter) {
        this.adminToken = props.adminToken() == null ? "" : props.adminToken();
        this.sessions = sessions;
        this.limiter = limiter;
        this.sessionTtlMs = props.adminSessionTtlMs();
        this.cookieSecure = props.cookieSecure();
    }

    @PostMapping("/login")
    public ResponseEntity<Void> login(@RequestBody LoginRequest req,
                                      HttpServletRequest request, HttpServletResponse response) {
        String ip = clientIp(request);
        if (!limiter.tryAcquire(ip)) {
            return ResponseEntity.status(429).build();
        }
        if (req.password() == null || req.password().isEmpty()) {
            limiter.onFailure(ip);
            return ResponseEntity.status(401).build();
        }
        byte[] given = req.password().getBytes(StandardCharsets.UTF_8);
        byte[] expected = adminToken.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(given, expected)) {
            limiter.onFailure(ip);
            return ResponseEntity.status(401).build();
        }
        limiter.reset(ip);
        String sessionToken = sessions.create();
        Cookie c = new Cookie(COOKIE_NAME, sessionToken);
        c.setHttpOnly(true);
        c.setSecure(cookieSecure);
        c.setAttribute("SameSite", "Strict"); // 跨站不携带,阻断 CSRF 类跨站写入
        c.setPath("/");
        c.setMaxAge((int) (sessionTtlMs / 1000));
        response.addCookie(c);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @org.springframework.web.bind.annotation.CookieValue(value = COOKIE_NAME, required = false)
            Cookie cookie, HttpServletResponse response) {
        clearCookie(response);
        if (cookie != null && cookie.getValue() != null && !cookie.getValue().isBlank()) {
            sessions.revoke(cookie.getValue());
        }
        return ResponseEntity.ok().build();
    }

    private void clearCookie(HttpServletResponse response) {
        Cookie c = new Cookie(COOKIE_NAME, "");
        c.setHttpOnly(true);
        c.setSecure(cookieSecure);
        c.setAttribute("SameSite", "Strict");
        c.setPath("/");
        c.setMaxAge(0);
        response.addCookie(c);
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }

    public record LoginRequest(String password) {}
}
