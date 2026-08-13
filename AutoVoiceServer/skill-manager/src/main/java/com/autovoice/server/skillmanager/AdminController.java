package com.autovoice.server.skillmanager;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** 管理端口令登录（demo 级）：password 匹配 ADMIN_TOKEN 后发 HttpOnly cookie。 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    static final String COOKIE_NAME = "skill_admin";

    private final String adminToken;

    public AdminController(SkillProperties props) {
        this.adminToken = props.adminToken() == null ? "" : props.adminToken();
    }

    @PostMapping("/login")
    public ResponseEntity<Void> login(@RequestBody LoginRequest req, HttpServletResponse response) {
        // 空 password 直接拒（先拒空再比：空口令不可能匹配非空 admin-token）
        if (req.password() == null || req.password().isEmpty()) {
            return ResponseEntity.status(401).build();
        }
        byte[] given = req.password().getBytes(StandardCharsets.UTF_8);
        byte[] expected = adminToken.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(given, expected)) {
            return ResponseEntity.status(401).build();
        }
        Cookie c = new Cookie(COOKIE_NAME, cookieValue(adminToken));
        c.setHttpOnly(true);
        c.setPath("/");
        c.setMaxAge(60 * 60 * 12);
        response.addCookie(c);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletResponse response) {
        Cookie c = new Cookie(COOKIE_NAME, "");
        c.setHttpOnly(true);
        c.setPath("/");
        c.setMaxAge(0);
        response.addCookie(c);
        return ResponseEntity.ok().build();
    }

    /** cookie 值 = SHA-256(token) 十六进制；拦截器同样计算比对。 */
    static String cookieValue(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public record LoginRequest(String password) {}
}
