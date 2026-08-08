package com.autovoice.server.nlutraditional;

import com.autovoice.server.contracts.Intent;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IflytekSemanticNormalizerTest {

    final IflytekSemanticNormalizer n = new IflytekSemanticNormalizer();

    private static String fixture(String name) throws Exception {
        return new String(Objects.requireNonNull(
                        IflytekSemanticNormalizerTest.class.getClassLoader().getResourceAsStream(name))
                .readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test
    void mapsClimateIntent() throws Exception {
        String vendor = fixture("iflytek-semantic-ac.json");
        Intent i = n.normalize(vendor, "nlu.iflytek.api");
        assertEquals("climate", i.domain());
        assertEquals("set_temperature", i.intent());
        assertEquals(24.0, (double) i.slots().get("temperature").value(), 0.001);
        assertEquals("driver", i.slots().get("zone").value());
        assertEquals("nlu.iflytek.api", i.source());
    }

    @Test
    void unknownWhenNoService() {
        Intent i = n.normalize("{\"code\":\"0\",\"data\":{\"result\":{\"intent\":{\"answer\":\"抱歉\"}}}}", "nlu.iflytek.api");
        assertTrue(i.isUnknown());
    }

    @Test
    void unknownWhenErrorCode() {
        Intent i = n.normalize("{\"code\":\"10110\"}", "nlu.iflytek.api");
        assertTrue(i.isUnknown());
    }
}
