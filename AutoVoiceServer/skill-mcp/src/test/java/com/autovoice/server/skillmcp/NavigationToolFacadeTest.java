package com.autovoice.server.skillmcp;

import com.autovoice.server.contracts.FunctionTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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

    @Test
    void broadAirportSearchMergesNearbyAndCitywideResultsAndDeduplicatesTerminals() throws Exception {
        Map<String, FunctionTool> tools = tools();
        List<String> calls = new ArrayList<>();
        NavigationToolFacade facade = new NavigationToolFacade(tools, (name, args) -> {
            calls.add(name + ":" + args);
            return switch (name) {
                case "maps_around_search" -> """
                        {"pois":[
                         {"name":"成都双流国际机场-T1航站楼","location":"103.9500,30.5700"},
                         {"name":"成都双流国际机场-T2航站楼","location":"103.9600,30.5800"}]}
                        """;
                case "maps_regeocode" -> "{\"regeocode\":{\"addressComponent\":{\"city\":\"成都市\"}}}";
                case "maps_text_search" -> """
                        {"pois":[
                         {"name":"成都双流国际机场","location":"103.9500,30.5700"},
                         {"name":"成都天府国际机场","location":"104.4410,30.3190"}]}
                        """;
                default -> throw new AssertionError("unexpected call: " + name);
            };
        });

        JsonNode candidates = JSON.readTree(facade.resolve(
                "{\"destinations\":[\"机场\"],\"location\":\"104.0665,30.5728\",\"limit\":3}"))
                .path("destinations").get(0).path("candidates");

        assertEquals(2, candidates.size());
        assertEquals("成都双流国际机场", candidates.get(0).path("poiname").asText());
        assertEquals("成都天府国际机场", candidates.get(1).path("poiname").asText());
        assertTrue(calls.stream().anyMatch(call -> call.startsWith("maps_regeocode:")));
        assertTrue(calls.stream().anyMatch(call -> call.startsWith("maps_text_search:")
                && call.contains("成都市")));
    }

    @Test
    void aroundSearchFailureFallsBackToTextSearch() throws Exception {
        Map<String, FunctionTool> tools = tools();
        List<String> calls = new ArrayList<>();
        NavigationToolFacade facade = new NavigationToolFacade(tools, (name, args) -> {
            calls.add(name);
            return switch (name) {
                case "maps_around_search" -> throw new McpToolException("around temporarily unavailable");
                case "maps_text_search" ->
                        "{\"pois\":[{\"name\":\"天府机场\",\"address\":\"空港大道\"}]}";
                case "maps_geo" -> "{\"results\":[{\"location\":\"104.441,30.319\"}]}";
                default -> throw new AssertionError("unexpected call: " + name);
            };
        });

        JsonNode candidates = JSON.readTree(facade.resolve(
                "{\"destinations\":[\"天府机场\"],\"location\":\"104.0665,30.5728\",\"city\":\"成都\"}"))
                .path("destinations").get(0).path("candidates");

        assertEquals("天府机场", candidates.get(0).path("poiname").asText());
        assertEquals(List.of("maps_around_search", "maps_text_search", "maps_geo"), calls);
    }

    @Test
    void oneGeocodeFailureKeepsSuccessfulCandidates() throws Exception {
        Map<String, FunctionTool> tools = tools();
        AtomicInteger geoCalls = new AtomicInteger();
        NavigationToolFacade facade = new NavigationToolFacade(tools, (name, args) -> {
            if (name.equals("maps_text_search")) {
                return "{\"pois\":[{\"name\":\"坏候选\",\"address\":\"未知\"},"
                        + "{\"name\":\"好候选\",\"address\":\"人民路\"}]}";
            }
            if (geoCalls.incrementAndGet() == 1) throw new McpToolException("one POI cannot geocode");
            return "{\"results\":[{\"location\":\"104.07,30.67\"}]}";
        });

        JsonNode candidates = JSON.readTree(facade.resolve(
                "{\"destinations\":[\"目的地\"],\"city\":\"成都\"}"))
                .path("destinations").get(0).path("candidates");

        assertEquals(1, candidates.size());
        assertEquals("好候选", candidates.get(0).path("poiname").asText());
    }

    @Test
    void airportTextResultsWithoutCoordinatesAreGeocodedBeforeNearbyTerminals() throws Exception {
        Map<String, FunctionTool> tools = tools();
        NavigationToolFacade facade = new NavigationToolFacade(tools, (name, args) -> switch (name) {
            case "maps_around_search" -> """
                    {"pois":[
                     {"name":"成都双流国际机场T1航站楼","address":"机场迎宾大道"},
                     {"name":"成都双流国际机场T2航站楼","address":"机场北二路"},
                     {"name":"新津机场","address":"桥津上街"}]}
                    """;
            case "maps_regeocode" -> "{\"regeocode\":{\"addressComponent\":{\"city\":\"成都市\"}}}";
            case "maps_text_search" -> """
                    {"pois":[
                     {"name":"成都双流国际机场","address":"机场北二路"},
                     {"name":"成都天府国际机场","address":"空港大道"}]}
                    """;
            case "maps_geo" -> args.contains("天府")
                    ? "{\"results\":[{\"location\":\"104.441,30.319\"}]}"
                    : "{\"results\":[{\"location\":\"103.956,30.570\"}]}";
            default -> throw new AssertionError("unexpected call: " + name);
        });

        JsonNode candidates = JSON.readTree(facade.resolve(
                "{\"destinations\":[\"机场\"],\"location\":\"104.0665,30.5728\",\"limit\":3}"))
                .path("destinations").get(0).path("candidates");

        assertEquals(3, candidates.size());
        assertEquals("成都双流国际机场", candidates.get(0).path("poiname").asText());
        assertEquals("成都天府国际机场", candidates.get(1).path("poiname").asText());
    }

    private static Map<String, FunctionTool> tools() {
        Map<String, FunctionTool> tools = new LinkedHashMap<>();
        tools.put("maps_text_search", new FunctionTool("maps_text_search", "", schema("keywords", "city")));
        tools.put("maps_around_search", new FunctionTool("maps_around_search", "",
                schema("keywords", "location", "radius")));
        tools.put("maps_regeocode", new FunctionTool("maps_regeocode", "", schema("location")));
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
