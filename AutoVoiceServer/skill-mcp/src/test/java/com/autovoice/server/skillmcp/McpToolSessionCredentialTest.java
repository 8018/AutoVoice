package com.autovoice.server.skillmcp;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.Test;

/** D14a 凭据引用:解析失败必须让连接失败,不得以未认证身份连下游。 */
class McpToolSessionCredentialTest {

    private static SkillConfig withCredential(String value) {
        return new SkillConfig("skill-x", "x", "d", "http://127.0.0.1:1/mcp",
                "X-Api-Key", value, "", true, 1L);
    }

    @Test
    void unresolvedCredentialReferenceFailsConnect() {
        // env: 引用指向未设置的变量 → 连接必须失败(而非无凭据连接)
        IOException error = assertThrows(IOException.class,
                () -> McpToolSession.connect(withCredential("env:AUTOVOICE_TEST_MISSING_KEY"), 500));
        assertTrue(error.getMessage().contains("credential unresolved"), error.getMessage());
    }
}
