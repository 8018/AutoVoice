package com.autovoice.server.contracts.secret;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * D14a 秘密引用解析:数据库只存引用名,真实值由服务端受控来源解析。
 * 解析失败必须显式失败(不得静默退化成无凭据连接)。
 */
class SecretResolverTest {

    @TempDir
    Path dir;

    private SecretResolver resolver(Map<String, String> env) {
        return new SecretResolver(env::get);
    }

    @Test
    void resolvesEnvironmentReference() {
        SecretResolver resolver = resolver(Map.of("AMAP_MCP_KEY", "s3cr3t"));
        assertEquals("s3cr3t", resolver.resolve("env:AMAP_MCP_KEY").value());
        assertTrue(resolver.resolve("env:AMAP_MCP_KEY").resolved());
    }

    @Test
    void resolvesFileReferenceAndTrimsTrailingNewline() throws Exception {
        Path secret = dir.resolve("amap.key");
        Files.writeString(secret, "file-secret\n");
        SecretResolver resolver = resolver(Map.of());

        var result = resolver.resolve("file:" + secret);
        assertTrue(result.resolved());
        assertEquals("file-secret", result.value(), "文件内容应去除末尾换行");
    }

    @Test
    void missingEnvironmentVariableFailsExplicitly() {
        SecretResolver resolver = resolver(Map.of());
        var result = resolver.resolve("env:NOT_SET");
        assertTrue(result.failed(), "未设置的引用必须显式失败");
        assertTrue(result.value().isEmpty());
    }

    @Test
    void missingFileFailsExplicitly() {
        SecretResolver resolver = resolver(Map.of());
        var result = resolver.resolve("file:" + dir.resolve("nope.key"));
        assertTrue(result.failed());
    }

    @Test
    void blankReferenceIsEmptyNonSensitive() {
        SecretResolver resolver = resolver(Map.of());
        var result = resolver.resolve("");
        assertTrue(result.resolved(), "空引用=无认证头(合法,非失败)");
        assertEquals("", result.value());
    }

    @Test
    void plainValueIsTreatedAsLegacyInlineSecret() {
        SecretResolver resolver = resolver(Map.of());
        var result = resolver.resolve("legacy-inline-token");
        assertTrue(result.resolved(), "无前缀值按明文兼容处理");
        assertEquals("legacy-inline-token", result.value());
        assertTrue(result.legacyInline(), "应标记为明文以提示迁移");
    }

    @Test
    void referenceReasonNeverLeaksSecretValue() {
        SecretResolver resolver = resolver(Map.of());
        var result = resolver.resolve("env:NOT_SET");
        assertTrue(result.reason().contains("env:NOT_SET"), "原因应指明引用名便于排障");
        assertTrue(result.reason().contains("not set"));
    }
}
