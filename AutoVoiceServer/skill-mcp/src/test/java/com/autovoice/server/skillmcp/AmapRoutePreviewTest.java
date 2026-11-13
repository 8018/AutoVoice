package com.autovoice.server.skillmcp;

import com.autovoice.server.contracts.NavigationRoutePreview.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AmapRoutePreviewTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Coordinate gps = new Coordinate(39.907291, 116.391317, CoordinateSystem.WGS84);
    private final Coordinate goal = new Coordinate(39.902753, 116.410886, CoordinateSystem.GCJ02);
    private final Coordinate waypoint = new Coordinate(39.909186, 116.404293, CoordinateSystem.GCJ02);

    @Test
    void convertsGpsAndPassesPreferenceAndWaypointsBeforeReturningRealEstimates() {
        var calls = new AtomicInteger();
        var service = new AmapRoutePreview(Clock.fixed(Instant.ofEpochMilli(4000), ZoneOffset.UTC),
                (path, params) -> {
                    if (calls.incrementAndGet() == 1) {
                        assertEquals("/v3/assistant/coordinate/convert", path);
                        assertEquals("gps", params.get("coordsys"));
                        assertEquals("116.391317,39.907291", params.get("locations"));
                        return json.readTree("{\"status\":\"1\",\"locations\":\"116.397499,39.908722\"}");
                    }
                    assertEquals("/v5/direction/driving", path);
                    assertEquals("116.397499,39.908722", params.get("origin"));
                    assertEquals("116.410886,39.902753", params.get("destination"));
                    assertEquals("116.404293,39.909186", params.get("waypoints"));
                    assertEquals("35", params.get("strategy"));
                    assertEquals("cost", params.get("show_fields"));
                    return json.readTree("""
                            {"status":"1","route":{"paths":[
                              {"distance":"3215","cost":{"duration":"1099","tolls":"0"}},
                              {"distance":"3413","cost":{"tolls":[]}}]}}
                            """);
                });
        var result = service.preview(new Request("op1", gps, goal, List.of(waypoint), Preference.AVOID_HIGHWAYS));
        assertEquals(Status.OK, result.status());
        assertEquals("op1", result.operationId());
        assertEquals("AMAP_WEB", result.source());
        assertEquals(4000L, result.plannedAtMs());
        assertEquals(new Coordinate(39.908722, 116.397499, CoordinateSystem.GCJ02), result.origin());
        assertEquals(List.of(waypoint), result.waypoints());
        assertEquals(2, result.routes().size());
        assertNotEquals(result.routes().get(0).id(), result.routes().get(1).id());
        assertEquals(3215L, result.routes().get(0).distanceMeters());
        assertEquals(1099L, result.routes().get(0).durationSeconds());
        assertEquals(0d, result.routes().get(0).tollYuan());
        assertNull(result.routes().get(1).durationSeconds());
        assertNull(result.routes().get(1).tollYuan());
    }

    @Test
    void doesNotQueryWithoutOriginOrAfterFailedConversion() {
        var calls = new AtomicInteger();
        var service = new AmapRoutePreview(Clock.systemUTC(), (path, params) -> {
            calls.incrementAndGet();
            assertEquals("/v3/assistant/coordinate/convert", path);
            return json.readTree("{\"status\":\"0\"}");
        });
        assertEquals(Status.ORIGIN_REQUIRED, service.preview(
                new Request("op1", null, goal, List.of(), Preference.DEFAULT)).status());
        assertEquals(0, calls.get());
        assertEquals(Status.UNAVAILABLE, service.preview(
                new Request("op2", gps, goal, List.of(), Preference.DEFAULT)).status());
        assertEquals(1, calls.get());
    }
}
