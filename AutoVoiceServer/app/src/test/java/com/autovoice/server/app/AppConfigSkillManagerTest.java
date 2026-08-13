package com.autovoice.server.app;

import static org.junit.jupiter.api.Assertions.*;

import com.autovoice.server.app.AppConfig.AutovoiceProperties;
import org.junit.jupiter.api.Test;

class AppConfigSkillManagerTest {

    @Test
    void skillManagerDefaults() {
        AutovoiceProperties props = new AutovoiceProperties(null, null, null, null, null, null, null);
        var sm = props.skillManager();
        assertEquals("", sm.url());
        assertEquals("", sm.serviceToken());
        assertEquals(600_000, sm.pollMs());
    }

    @Test
    void skillManagerParsed() {
        AutovoiceProperties props = new AutovoiceProperties(null, null, null, null, null, null,
                new AutovoiceProperties.SkillManager("http://127.0.0.1:8083", "tok", 60_000));
        assertEquals("http://127.0.0.1:8083", props.skillManager().url());
        assertEquals(60_000, props.skillManager().pollMs());
    }

    @Test
    void blankServiceTokenWithUrlFailsFast() {
        // 平台已接入（url 非空）但 token 空白 = webhook 端点静默开门：bean 装配期快速失败
        AutovoiceProperties props = new AutovoiceProperties(null, null, null, null, null, null,
                new AutovoiceProperties.SkillManager("http://127.0.0.1:8083", "", 60_000));
        assertThrows(IllegalStateException.class,
                () -> new AppConfig().skillRefreshController(null, props));
    }

    @Test
    void blankServiceTokenWithoutUrlAllowed() {
        // 平台未接入（url 空）：token 空白允许（webhook 端点 feature 关闭）
        AutovoiceProperties props = new AutovoiceProperties(null, null, null, null, null, null,
                new AutovoiceProperties.SkillManager("", "", 60_000));
        assertDoesNotThrow(() -> new AppConfig().skillRefreshController(null, props));
    }
}
