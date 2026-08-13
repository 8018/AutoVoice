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
}
