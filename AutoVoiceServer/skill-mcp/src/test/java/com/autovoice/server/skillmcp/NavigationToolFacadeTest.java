package com.autovoice.server.skillmcp;

import com.autovoice.server.contracts.FunctionTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NavigationToolFacadeTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void aroundSearchReturnsNavigationReadyCoordinatesWithoutGeo() throws Exception {
        Map<String, FunctionTool> tools = tools();
        AtomicInteger geoCalls = new AtomicInteger();
        NavigationToolFacade facade = new NavigationToolFacade(tools, (name, args) -> {
            if (name.equals("maps_geo")) geoCalls.incrementAndGet();
            assertTrue(args.contains("116.30,39.90"));
            return "{\"pois\":[{\"name\":\"最近咖啡店\",\"address\":\"中关村\",\"location\":\"116.31,39.91\"}]}";
        });

        JsonNode result = JSON.readTree(facade.resolve(
                "{\"destinations\":[\"咖啡店\"],\"location\":\"116.30,39.90\"}"));
        JsonNode candidate = result.path("destinations").get(0).path("candidates").get(0);
        assertEquals("最近咖啡店", candidate.path("poiname").asText());
        assertEquals(39.91, candidate.path("lat").asDouble(), 0.0001);
        assertEquals(116.31, candidate.path("lon").asDouble(), 0.0001);
        assertEquals(0, geoCalls.get());
    }

    @Test
    void geocodesSearchResultsMissingCoordinates() throws Exception {
        Map<String, FunctionTool> tools = tools();
        NavigationToolFacade facade = new NavigationToolFacade(tools, (name, args) -> {
            if (name.equals("maps_text_search")) {
                return "{\"pois\":[{\"name\":\"爱情广场\",\"address\":\"竞秀区\"}]}";
            }
            assertTrue(args.contains("爱情广场"));
            return "{\"geocodes\":[{\"location\":\"115.4696,38.8654\"}]}";
        });

        JsonNode candidate = JSON.readTree(facade.resolve(
                "{\"destinations\":[\"爱情广场\"],\"city\":\"保定\"}"))
                .path("destinations").get(0).path("candidates").get(0);
        assertEquals("爱情广场", candidate.path("poiname").asText());
        assertEquals(38.8654, candidate.path("lat").asDouble(), 0.0001);
    }

    private static Map<String, FunctionTool> tools() {
        Map<String, FunctionTool> tools = new LinkedHashMap<>();
        tools.put("maps_text_search", new FunctionTool("maps_text_search", "", schema("keywords", "city")));
        tools.put("maps_around_search", new FunctionTool("maps_around_search", "",
                schema("keywords", "location", "radius")));
        tools.put("maps_geo", new FunctionTool("maps_geo", "", schema("address", "city")));
        return tools;
    }

    private static String schema(String... names) {
        StringBuilder out = new StringBuilder("{\"type\":\"object\",\"properties\":{");
        for (int i = 0; i < names.length; i++) {
            if (i > 0) out.append(',');
            out.append('"').append(names[i]).append("\":{\"type\":\"string\"}");
        }
        return out.append("}}").toString();
    }
}
