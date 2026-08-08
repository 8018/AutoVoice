package com.autovoice.server.contracts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntentSerializationTest {

    static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void roundTripsCanonicalIntent() throws Exception {
        Intent i = Intent.of("1.0", "climate", "set_temperature",
            Map.of("temperature", SlotValue.number(24), "zone", SlotValue.enumValue("driver")),
            0.95, "nlu.iflytek.api", null);
        String json = MAPPER.writeValueAsString(i);
        Intent back = MAPPER.readValue(json, Intent.class);
        assertEquals("climate", back.domain());
        assertEquals(24.0, (double) back.slots().get("temperature").value(), 0.001);
        assertEquals("driver", back.slots().get("zone").value());
        assertFalse(back.isUnknown());
    }

    @Test
    void unknownIntentRoundTrip() throws Exception {
        Intent u = Intent.unknown("nlu.iflytek.api");
        Intent back = MAPPER.readValue(MAPPER.writeValueAsString(u), Intent.class);
        assertTrue(back.isUnknown());
    }
}
