package com.autovoice.server.agentloop;

import com.autovoice.server.contracts.SessionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class NavigationRequestContextTest {
    private final ObjectMapper json = new ObjectMapper();
    @Test void replacesModelCoordinatesAndPreservesExplicitDestinations() throws Exception {
        var ctx = new SessionContext("s", "zh", Map.of("latitude", 30.65, "longitude", 104.06));
        for (String args : new String[]{"{\"destinations\":[\"北京南站\"]}",
                "{\"destinations\":[\"北京南站\"],\"location\":\"116.4,39.9\"}"}) {
            var result = json.readTree(NavigationRequestContext.bind("resolve_navigation", args, ctx));
            assertEquals("104.06,30.65", result.path("location").asText());
            assertEquals("北京南站", result.path("destinations").get(0).asText());
        }
    }
    @Test void missingAndInvalidDeviceFixCannotBeSuppliedByModel() throws Exception {
        for (var attrs : java.util.List.of(Map.<String,Object>of(),
                Map.<String,Object>of("latitude", 91, "longitude", 104),
                Map.<String,Object>of("latitude", Double.NaN, "longitude", 104))) {
            var ctx = new SessionContext("s", "zh", attrs);
            var result = json.readTree(NavigationRequestContext.bind("resolve_navigation",
                    "{\"destinations\":[\"火车站\"],\"location\":\"116.4,39.9\"}", ctx));
            assertFalse(result.has("location"));
        }
    }
    @Test void unrelatedToolsRemainUntouched() {
        assertEquals("{}", NavigationRequestContext.bind("car_control", "{}", null));
    }
}
