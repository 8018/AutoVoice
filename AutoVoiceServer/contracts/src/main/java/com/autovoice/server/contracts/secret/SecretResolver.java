package com.autovoice.server.contracts.secret;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

/**
 * D14a 秘密引用解析(数据库只存引用名,真实值由服务端受控来源解析):
 *
 * <ul>
 *   <li>{@code env:NAME} → 环境变量(由部署方注入,不入库);</li>
 *   <li>{@code file:/path} → 文件内容(首行去空白;建议 0600 且属主为服务账号);</li>
 *   <li>空串 → 无凭据(合法:该 Skill 不需要认证头);</li>
 *   <li>其他值 → 视为历史明文内联值(兼容旧数据,并标记 legacyInline 提示迁移)。</li>
 * </ul>
 *
 * <p>解析失败(环境变量未设置/文件不存在)必须**显式失败**——调用方应让该连接失败,
 * 绝不能静默退化成"无凭据连接"(那会以未认证身份访问下游)。\</p>
 */
public final class SecretResolver {

    /** 解析结果:值、是否解析成功、是否为历史明文、失败原因(不含秘密值)。 */
    public record Resolution(String value, boolean resolved, boolean legacyInline, String reason) {
        public boolean failed() {
            return !resolved;
        }

        static Resolution ok(String value) {
            return new Resolution(value, true, false, "");
        }

        static Resolution legacy(String value) {
            return new Resolution(value, true, true, "legacy inline secret (migrate to env:/file:)");
        }

        static Resolution failure(String reason) {
            return new Resolution("", false, false, reason);
        }
    }

    private final Function<String, String> environment;

    /** @param environment 环境变量读取器(生产用 System::getenv;测试注入) */
    public SecretResolver(Function<String, String> environment) {
        this.environment = java.util.Objects.requireNonNull(environment, "environment");
    }

    public Resolution resolve(String reference) {
        if (reference == null || reference.isBlank()) {
            return Resolution.ok("");
        }
        if (reference.startsWith("env:")) {
            String name = reference.substring("env:".length()).trim();
            String value = name.isEmpty() ? null : environment.apply(name);
            if (value == null || value.isEmpty()) {
                return Resolution.failure("environment variable not set: " + reference);
            }
            return Resolution.ok(value);
        }
        if (reference.startsWith("file:")) {
            String path = reference.substring("file:".length()).trim();
            try {
                return Resolution.ok(Files.readString(Path.of(path)).trim());
            } catch (Exception error) {
                return Resolution.failure("secret file unreadable: " + reference);
            }
        }
        // 无前缀:历史明文内联值(兼容,但提示迁移)
        return Resolution.legacy(reference);
    }
}
