package com.autovoice.server.skillmanager;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** 启动期 token 快速失败（WebMvcConfig 构造）：空 token = 管理界面"静默开门"，必须拒启。 */
class SkillPropertiesFailFastTest {

    @Test
    void blankAdminTokenFailsFast() {
        assertThrows(IllegalStateException.class,
                () -> new WebMvcConfig(new SkillProperties("./db", "", "svc", "", 5_000)));
        assertThrows(IllegalStateException.class,
                () -> new WebMvcConfig(new SkillProperties("./db", "  ", "svc", "", 5_000)));
    }

    @Test
    void blankServiceTokenWithWebhookUrlFailsFast() {
        // 启用 webhook 推送（gatewayWebhookUrl 非空）才要求 service-token
        assertThrows(IllegalStateException.class,
                () -> new WebMvcConfig(new SkillProperties("./db", "admin", "",
                        "http://127.0.0.1:8080", 5_000)));
    }

    @Test
    void blankServiceTokenWithoutWebhookUrlAllowed() {
        // feature 未启用（webhook 空）：service-token 空允许启动（单机开发）
        assertDoesNotThrow(() -> new WebMvcConfig(new SkillProperties("./db", "admin", "", "", 5_000)));
    }

    @Test
    void configuredTokensPass() {
        assertDoesNotThrow(() -> new WebMvcConfig(
                new SkillProperties("./db", "admin", "svc", "http://127.0.0.1:8080", 5_000)));
    }
}
