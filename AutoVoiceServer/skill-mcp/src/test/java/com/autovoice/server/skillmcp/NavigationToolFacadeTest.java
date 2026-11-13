package com.autovoice.server.skillmcp;

import com.autovoice.server.contracts.FunctionTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NavigationToolFacadeTest {
    @Test
    void terminalSearchAcceptsEquivalentTerminalNameAndRejectsAirportShops() throws Exception {
        var facade = new NavigationToolFacade(tools(), (name, args) -> """
                {"pois":[{"name":"虹桥国际机场T2便利店","location":"121.32,31.20"},
                         {"name":"虹桥国际机场2号航站楼","location":"121.33,31.20"}]}
                """);
        var result = JSON.readTree(facade.resolve("""
                {"destinations":["虹桥机场 T2"],"city":"上海"}
                """));
        var matches = result.path("destinations").get(0).path("candidates");
        assertEquals(1, matches.size());
        assertEquals("虹桥国际机场2号航站楼", matches.get(0).path("poiname").asText());
    }

    @Test
    void fixedCityDropsOutOfCityProviderResults() throws Exception {
        var facade = new NavigationToolFacade(tools(), (name, args) -> {
            if (name.equals("maps_geo")) return "{}";
            return """
                    {"pois":[{"name":"万象城","cityname":"宁波市","location":"121.55,29.87"}]}
                    """;
        });
        var result = JSON.readTree(facade.resolve("""
                {"destinations":["万象城"],"city":"杭州","location":"116.30,39.90"}
                """));
        assertTrue(result.path("destinations").get(0).path("candidates").isEmpty());
    }

    @Test
    void coordinateLessReferenceUsesVerifiedLandmarkBeforeAroundSearch() throws Exception {
        var facade = new NavigationToolFacade(tools(), (name, args) -> switch (name) {
            case "maps_text_search" -> """
                    {"pois":[{"name":"人民广场","cityname":"上海市","address":"人民大道"}]}
                    """;
            case "maps_geo" -> {
                assertTrue(args.contains("上海"));
                assertTrue(args.contains("人民大道"));
                yield """
                        {"geocodes":[{"formatted_address":"上海市人民大道人民广场","location":"121.47,31.23"}]}
                        """;
            }
            case "maps_around_search" -> {
                assertTrue(args.contains("121.47,31.23"));
                yield """
                        {"pois":[{"name":"咖啡店","location":"121.48,31.23"}]}
                        """;
            }
            default -> throw new AssertionError(name);
        });
        var result = JSON.readTree(facade.resolve("""
                {"destinations":["咖啡店"],"city":"上海","reference":"人民广场"}
                """));
        assertEquals("咖啡店", result.path("destinations").get(0).path("candidates").get(0)
                .path("poiname").asText());
    }

    @Test
    void ambiguousReferenceRequestsAnAddressAndDoesNotUseVehicle() throws Exception {
        var facade = new NavigationToolFacade(tools(), (name, args) -> {
            assertEquals("maps_text_search", name);
            return """
                    {"pois":[{"name":"人民广场","location":"121.47,31.23"},
                             {"name":"人民广场","location":"121.57,31.20"}]}
                    """;
        });
        var result = JSON.readTree(facade.resolve("""
                {"destinations":["咖啡店"],"city":"上海","reference":"人民广场","location":"116.30,39.90"}
                """));
        assertTrue(result.path("destinations").get(0).path("candidates").isEmpty());
        assertTrue(result.path("message").asText().contains("参考地点"));
    }

    @Test
    void rejectsInvalidStopInsteadOfSilentlyShorteningRoute() {
        var facade = new NavigationToolFacade(tools(), (name, args) -> {
            throw new AssertionError("invalid route must be rejected before searching");
        });
        org.junit.jupiter.api.Assertions.assertThrows(McpToolException.class, () -> facade.resolve("""
                {"destinations":["机场"," ","火车站"],"location":"116.30,39.90"}
                """));
    }

    @Test
    void recognizesSpokenHangzhouScopeWithoutModelField() throws Exception {
        var facade = new NavigationToolFacade(tools(), (name, args) -> {
            assertEquals("maps_text_search", name);
            assertTrue(args.contains("\"city\":\"杭州\""));
            return """
                    {"pois":[{"name":"万象城","location":"120.22,30.25"}]}
                    """;
        });
        var result = JSON.readTree(facade.resolve("""
                {"destinations":["杭州的万象城"]}
                """));
        assertEquals("万象城", result.path("destinations").get(0).path("candidates").get(0)
                .path("poiname").asText());
    }

    @Test
    void specifiedCityAndDistrictTakePrecedenceOverVehicleCoordinates() throws Exception {
        var facade = new NavigationToolFacade(tools(), (name, args) -> {
            assertEquals("maps_text_search", name);
            assertTrue(args.contains("杭州"));
            assertTrue(args.contains("上城区"));
            return """
                    {"pois":[{"name":"万象城","address":"上城区富春路","location":"120.22,30.25"}]}
                    """;
        });
        var result = JSON.readTree(facade.resolve("""
                {"destinations":["万象城"],"city":"杭州","district":"上城区","location":"116.30,39.90"}
                """));
        assertEquals("万象城", result.path("destinations").get(0).path("candidates").get(0)
                .path("poiname").asText());
    }

    @Test
    void referenceSearchCentersOnResolvedLandmark() throws Exception {
        var facade = new NavigationToolFacade(tools(), (name, args) -> switch (name) {
            case "maps_text_search" -> {
                assertTrue(args.contains("人民广场"));
                yield """
                        {"pois":[{"name":"人民广场","address":"黄浦区","location":"121.47,31.23"}]}
                        """;
            }
            case "maps_around_search" -> {
                assertTrue(args.contains("121.47,31.23"));
                assertTrue(args.contains("咖啡店"));
                yield """
                        {"pois":[{"name":"咖啡店","address":"黄浦区","location":"121.48,31.23"}]}
                        """;
            }
            default -> throw new AssertionError(name);
        });
        var result = JSON.readTree(facade.resolve("""
                {"destinations":["人民广场附近的咖啡店"],"city":"上海","location":"116.30,39.90"}
                """));
        assertEquals("咖啡店", result.path("destinations").get(0).path("candidates").get(0)
                .path("poiname").asText());
    }

    @Test
    void doesNotSubstituteAirportCenterForRequestedTerminal() throws Exception {
        var facade = new NavigationToolFacade(tools(), (name, args) -> {
            assertEquals("maps_text_search", name);
            return """
                    {"pois":[{"name":"上海虹桥国际机场","location":"121.33,31.20"}]}
                    """;
        });
        var result = JSON.readTree(facade.resolve("""
                {"destinations":["虹桥机场 T2"],"city":"上海"}
                """));
        assertTrue(result.path("destinations").get(0).path("candidates").isEmpty());
    }

    @Test
    void keepsOnlyThreeDistinctUnseenCandidatesInNextBatch() throws Exception {
        var facade = new NavigationToolFacade(tools(), (name, args) -> """
                {"pois":[
                  {"name":"门店A","location":"120.1,30.1"},
                  {"name":"门店B","location":"120.2,30.1"},
                  {"name":"门店B","location":"120.2,30.1"},
                  {"name":"门店C","location":"120.3,30.1"},
                  {"name":"门店D","location":"120.4,30.1"},
                  {"name":"门店E","location":"120.5,30.1"}]}
                """);
        var result = JSON.readTree(facade.resolve("""
                {"destinations":["门店"],"city":"杭州","limit":5,
                 "excluded":[{"poiname":"门店A","lat":30.1,"lon":120.1}]}
                """));
        var candidates = result.path("destinations").get(0).path("candidates");
        assertEquals(3, candidates.size());
        assertEquals("门店B", candidates.get(0).path("poiname").asText());
        assertEquals("门店D", candidates.get(2).path("poiname").asText());
    }

    @Test
    void missingPositionRequestsCityInsteadOfUnboundedNearbySearch() throws Exception {
        var facade = new NavigationToolFacade(tools(), (name, args) -> {
            throw new AssertionError(name);
        });
        var result = JSON.readTree(facade.resolve("""
                {"destinations":["附近的充电站"]}
                """));
        assertTrue(result.path("destinations").get(0).path("candidates").isEmpty());
        assertTrue(result.path("message").asText().contains("城市"));
    }

    @Test
    void constrainedSearchMatchesNamesAndProviderCategoriesBeforeApplyingDisplayLimit() throws Exception {
        var facade = new NavigationToolFacade(tools(), (name, args) -> """
                {"pois":[{"name":"其他品牌","type":"餐饮服务;咖啡厅","location":"120.1,30.1"},
                         {"name":"目标品牌","type":"餐饮服务;咖啡厅","location":"120.2,30.2"}]}
                """);
        var result = JSON.readTree(facade.resolve("""
                {"destinations":["咖啡厅"],"city":"杭州","limit":1,
                 "constraints":[{"brand":"目标品牌","category":"咖啡厅"}]}
                """));
        var candidate = result.path("destinations").get(0).path("candidates").get(0);
        assertEquals("目标品牌", candidate.path("poiname").asText());
        assertEquals("amap_poi", candidate.path("conditionEvidence").get(0).path("source").asText());
        assertTrue(candidate.path("matchSummary").asText().contains("高德"));
        assertFalse(result.path("blocked").asBoolean());
        assertTrue(result.path("search").path("constraints").isArray());
    }

    @Test
    void constrainedSearchVerifiesCategoryAndCoordinatesFromMatchingPoiDetail() throws Exception {
        var advertised = new LinkedHashMap<>(tools());
        advertised.put("maps_search_detail", new FunctionTool("maps_search_detail", "",
                """
                {"type":"object","properties":{"id":{"type":"string"}},"required":["id"]}
                """));
        var facade = new NavigationToolFacade(advertised, (name, args) -> switch (name) {
            case "maps_text_search" -> """
                    {"pois":[{"name":"目标品牌咖啡","id":"coffee-id","typecode":"050500"},
                             {"name":"另一目标品牌","id":"other-id","typecode":"050500"}]}
                    """;
            case "maps_search_detail" -> args.contains("coffee-id") ? """
                    {"id":"coffee-id","type":"餐饮服务;咖啡厅","location":"120.1,30.1"}
                    """ : """
                    {"id":"wrong-id","type":"餐饮服务;咖啡厅","location":"120.2,30.2"}
                    """;
            default -> throw new AssertionError(name);
        });
        var result = JSON.readTree(facade.resolve("""
                {"destinations":["咖啡厅"],"city":"杭州",
                 "constraints":[{"brand":"目标品牌","category":"咖啡厅"}]}
                """));
        var candidates = result.path("destinations").get(0).path("candidates");
        assertEquals(1, candidates.size());
        assertEquals("目标品牌咖啡", candidates.get(0).path("poiname").asText());
        assertEquals("type", candidates.get(0).path("conditionEvidence").get(1).path("field").asText());
    }

    @Test
    void searchesProviderAliasWithoutInferringUnregisteredFeatures() throws Exception {
        var advertised = new LinkedHashMap<>(tools());
        advertised.put("maps_search_detail", new FunctionTool("maps_search_detail", "",
                """
                {"type":"object","properties":{"id":{"type":"string"}},"required":["id"]}
                """));
        var facade = new NavigationToolFacade(advertised, (name, args) -> switch (name) {
            case "maps_text_search" -> """
                    {"pois":[{"name":"正式名称","id":"poi-id"}]}
                    """;
            case "maps_search_detail" -> """
                    {"id":"poi-id","alias":"附近人常用名","location":"120.1,30.1"}
                    """;
            default -> throw new AssertionError(name);
        });
        var result = JSON.readTree(facade.resolve("""
                {"destinations":["附近人常用名"],"city":"杭州",
                 "constraints":[{"alias":"附近人常用名"}]}
                """));
        assertFalse(result.path("blocked").asBoolean());
        var candidate = result.path("destinations").get(0).path("candidates").get(0);
        assertEquals("正式名称", candidate.path("poiname").asText());
        assertEquals("alias", candidate.path("conditionEvidence").get(0).path("field").asText());
    }

    @Test
    void featureAndOpeningWithoutTrustedEvidenceBlockSelection() throws Exception {
        var facade = new NavigationToolFacade(tools(), (name, args) -> """
                {"pois":[{"name":"亲子餐厅","location":"120.1,30.1",
                          "openNow":true,"features":["适合带孩子"]}]}
                """);
        var result = JSON.readTree(facade.resolve("""
                {"destinations":["餐厅"],"city":"杭州",
                 "constraints":[{"openNow":true,"feature":"适合带孩子"}]}
                """));
        assertTrue(result.path("blocked").asBoolean());
        assertTrue(result.path("message").asText().contains("当前营业"));
        assertTrue(result.path("message").asText().contains("适合带孩子"));
    }

    @Test
    void chargingPlanUsesMissingTrustedInputsInsteadOfModelSuppliedTelemetry() throws Exception {
        var facade = new NavigationToolFacade(tools(), (name, args) -> {
            throw new AssertionError("charging request must not fall through to location search");
        });
        var result = JSON.readTree(facade.resolve("""
                {"destinations":["充电站","休息区"],"city":"杭州",
                 "chargingPlan":{"mode":"charge_and_rest","minimumArrivalSoc":20,
                 "soc":85,"socSampledAt":"2026-11-13T12:00:00Z","energyPerKm":0.15}}
                """));
        assertTrue(result.path("blocked").asBoolean());
        assertTrue(result.path("message").asText().contains("路线"));
        assertTrue(result.path("message").asText().contains("荷电状态"));
        assertTrue(result.path("message").asText().contains("兼容"));
        assertTrue(result.path("message").asText().contains("能耗"));
        assertTrue(result.path("destinations").isMissingNode());
    }

    @Test
    void readOnlyRoutePreferenceSurvivesDestinationResolution() throws Exception {
        var facade = new NavigationToolFacade(tools(), (name, args) -> """
                {"pois":[{"name":"科技馆","location":"120.1,30.1"}]}
                """);
        var result = JSON.readTree(facade.resolve("""
                {"destinations":["科技馆"],"city":"杭州","routePreviewPreference":"LESS_TOLLS"}
                """));
        assertEquals("LESS_TOLLS", result.path("routePreviewPreference").asText());
        assertEquals("LESS_TOLLS", result.path("search").path("routePreviewPreference").asText());
    }

    @Test
    void chargingSearchReturnsStationWithoutClaimingAvailability() throws Exception {
        var facade = new NavigationToolFacade(tools(), (name, args) -> """
                {"pois":[{"name":"新能源充电站","location":"120.1,30.1"}]}
                """);
        var result = JSON.readTree(facade.resolve("""
                {"destinations":["附近的充电站"],"location":"120.1,30.1"}
                """));
        assertEquals("新能源充电站", result.path("destinations").get(0).path("candidates").get(0)
                .path("poiname").asText());
        assertTrue(result.path("availabilityNotice").asText().contains("未知"));
    }

    @Test
    void airportRecallDoesNotLoseFartherAirportBehindManyTerminals() throws Exception {
        NavigationToolFacade facade = new NavigationToolFacade(tools(), (name, args) -> {
            if (name.equals("maps_regeocode")) return "{\"city\":\"成都\"}";
            if (name.equals("maps_geo")) return "{}";
            return """
                {"pois":[
                {"name":"成都双流国际机场T1航站楼","location":"103.95,30.57"},
                {"name":"成都双流国际机场T2航站楼","location":"103.95,30.57"},
                {"name":"成都双流国际机场停车场","location":"103.95,30.57"},
                {"name":"机场酒店","location":"103.95,30.57"},
                {"name":"成都双流国际机场","location":"103.95,30.57"},
                {"name":"成都天府国际机场","location":"104.44,30.31"}]}
                """;
        });
        var candidates = JSON.readTree(facade.resolve("{\"destinations\":[\"机场\"],\"location\":\"104.06,30.65\"}"))
                .path("destinations").get(0).path("candidates");
        assertEquals(2, candidates.size());
        assertEquals("成都双流国际机场", candidates.get(0).path("poiname").asText());
        assertEquals("成都天府国际机场", candidates.get(1).path("poiname").asText());
    }

    @Test
    void explicitlyRequestedAirportParkingIsPreserved() throws Exception {
        NavigationToolFacade facade = new NavigationToolFacade(tools(), (name, args) ->
                "{\"pois\":[{\"name\":\"天府机场停车场\",\"location\":\"104.44,30.31\"}]}");
        var candidates = JSON.readTree(facade.resolve("{\"destinations\":[\"天府机场停车场\"],\"location\":\"104.06,30.65\"}"))
                .path("destinations").get(0).path("candidates");
        assertEquals("天府机场停车场", candidates.get(0).path("poiname").asText());
    }
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
    void namedOrRemoteFuzzyPlaceUsesTextSearchInsteadOfVehicleCenteredSearch() throws Exception {
        List<String> calls = new ArrayList<>();
        NavigationToolFacade facade = new NavigationToolFacade(tools(), (name, args) -> {
            calls.add(name);
            if (!name.equals("maps_text_search")) {
                throw new AssertionError("named remote place must not be searched around the vehicle: " + name);
            }
            assertTrue(args.contains("保定有大旗杆的地方"));
            return "{\"pois\":[{\"name\":\"保定爱情广场\",\"address\":\"竞秀区\","
                    + "\"location\":\"115.4696,38.8654\"}]}";
        });

        JsonNode candidate = JSON.readTree(facade.resolve(
                "{\"destinations\":[\"保定有大旗杆的地方\"],\"location\":\"104.06,30.65\"}"))
                .path("destinations").get(0).path("candidates").get(0);

        assertEquals(List.of("maps_text_search"), calls);
        assertEquals("保定爱情广场", candidate.path("poiname").asText());
        assertEquals(38.8654, candidate.path("lat").asDouble(), 0.0001);
    }

    @Test
    void coordinateLessAroundSearchFallsBackToTextSearchBeforeGeo() throws Exception {
        List<String> calls = new ArrayList<>();
        NavigationToolFacade facade = new NavigationToolFacade(tools(), (name, args) -> {
            calls.add(name);
            return switch (name) {
                case "maps_around_search" ->
                        "{\"pois\":[{\"name\":\"附近咖啡店\",\"address\":\"人民路\"}]}";
                case "maps_text_search" ->
                        "{\"pois\":[{\"name\":\"附近咖啡店\",\"address\":\"人民路\","
                                + "\"location\":\"104.07,30.67\"}]}";
                default -> throw new AssertionError("geocode must not be needed: " + name);
            };
        });

        JsonNode candidate = JSON.readTree(facade.resolve(
                "{\"destinations\":[\"咖啡店\"],\"location\":\"104.06,30.65\"}"))
                .path("destinations").get(0).path("candidates").get(0);

        assertEquals(List.of("maps_around_search", "maps_text_search"), calls);
        assertEquals("附近咖啡店", candidate.path("poiname").asText());
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
                        "{\"pois\":[{\"name\":\"附近咖啡店\",\"address\":\"人民路\"}]}";
                case "maps_geo" -> "{\"results\":[{\"location\":\"104.441,30.319\"}]}";
                default -> throw new AssertionError("unexpected call: " + name);
            };
        });

        JsonNode candidates = JSON.readTree(facade.resolve(
                "{\"destinations\":[\"咖啡店\"],\"location\":\"104.0665,30.5728\"}"))
                .path("destinations").get(0).path("candidates");

        assertEquals("附近咖啡店", candidates.get(0).path("poiname").asText());
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
    void spacesFallbackGeocodesAcrossOneSharedFacade() throws Exception {
        AtomicLong now = new AtomicLong();
        List<Long> sleeps = new ArrayList<>();
        NavigationToolFacade facade = new NavigationToolFacade(tools(), (name, args) -> {
            if (name.equals("maps_text_search")) {
                return "{\"pois\":[{\"name\":\"候选一\"},{\"name\":\"候选二\"}]}";
            }
            return "{\"geocodes\":[{\"location\":\"115.4696,38.8654\"}]}";
        }, now::get, millis -> {
            sleeps.add(millis);
            now.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis));
        });

        JsonNode candidates = JSON.readTree(facade.resolve(
                "{\"destinations\":[\"模糊地点\"],\"city\":\"保定\",\"limit\":2}"))
                .path("destinations").get(0).path("candidates");

        assertEquals(2, candidates.size());
        assertEquals(List.of(400L), sleeps);
    }

    @Test
    void retriesOnlyExplicitQpsFailureAfterBackoff() throws Exception {
        AtomicLong now = new AtomicLong();
        List<Long> sleeps = new ArrayList<>();
        AtomicInteger geoCalls = new AtomicInteger();
        NavigationToolFacade facade = new NavigationToolFacade(tools(), (name, args) -> {
            if (name.equals("maps_text_search")) {
                return "{\"pois\":[{\"name\":\"爱情广场\",\"address\":\"竞秀区\"}]}";
            }
            if (geoCalls.getAndIncrement() == 0) {
                throw new McpToolException("API 调用失败：CUQPS_HAS_EXCEEDED_THE_LIMIT");
            }
            return "{\"geocodes\":[{\"location\":\"115.4696,38.8654\"}]}";
        }, now::get, millis -> {
            sleeps.add(millis);
            now.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis));
        });

        JsonNode candidates = JSON.readTree(facade.resolve(
                "{\"destinations\":[\"爱情广场\"],\"city\":\"保定\"}"))
                .path("destinations").get(0).path("candidates");

        assertEquals(1, candidates.size());
        assertEquals(2, geoCalls.get());
        assertEquals(List.of(1000L), sleeps);
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

    @Test
    void multiStopKeepsAlternativesEvenWithALimitOfOne() throws Exception {
        NavigationToolFacade facade = new NavigationToolFacade(tools(), (name, args) -> {
            if (name.equals("maps_regeocode")) return "{\"city\":\"成都\"}";
            if (name.equals("maps_geo")) return "{}";
            if (args.contains("机场")) return """
                    {"pois":[
                     {"name":"成都双流国际机场","location":"103.9500,30.5700"},
                     {"name":"成都天府国际机场","location":"104.4410,30.3190"}]}
                    """;
            return """
                    {"pois":[
                     {"name":"春熙路","location":"104.0810,30.6570"},
                     {"name":"春熙路地铁站","location":"104.0820,30.6580"}]}
                    """;
        });

        JsonNode result = JSON.readTree(facade.resolve(
                "{\"destinations\":[\"春熙路\",\"机场\"],\"location\":\"104.0665,30.5728\",\"limit\":1}"));

        assertEquals(2, result.path("destinations").size());
        assertEquals(2, result.path("destinations").get(0).path("candidates").size());
        assertEquals(2, result.path("destinations").get(1).path("candidates").size());
        assertTrue(result.path("instruction").asText().contains("草稿"));
    }

    @Test
    void savedAddressIsASymbolicStopAndNeverSearchedAsAPoi() throws Exception {
        NavigationToolFacade facade = new NavigationToolFacade(tools(), (name, args) -> {
            assertFalse(args.contains("回家"), "The maps provider must not receive the saved place name");
            if (name.equals("maps_geo")) return "{}";
            return "{\"pois\":[{\"name\":\"机场\",\"location\":\"104.44,30.31\"}]}";
        });

        JsonNode stops = JSON.readTree(facade.resolve("""
                {"destinations":["机场","回家"],"location":"104.06,30.65"}
                """)).path("destinations");

        assertEquals("home", stops.get(1).path("savedPlace").asText());
        assertFalse(stops.get(1).has("candidates"));
    }

    @Test
    void genericTrainStationWaypointRanksNearbyPointFirst() throws Exception {
        List<String> calls = new ArrayList<>();
        NavigationToolFacade facade = new NavigationToolFacade(tools(), (name, args) -> {
            calls.add(name);
            if (name.equals("maps_regeocode")) return "{\"city\":\"成都市\"}";
            if (name.equals("maps_around_search")) return """
                    {"pois":[
                     {"name":"外地火车站","location":"116.40,39.90"},
                     {"name":"成都东站","location":"104.14,30.63"}]}
                    """;
            if (name.equals("maps_text_search")) return """
                    {"pois":[{"name":"成都天府国际机场","location":"104.44,30.31"}]}
                    """;
            throw new AssertionError("unexpected call: " + name);
        });

        JsonNode result = JSON.readTree(facade.resolve("""
                {"destinations":["火车站","成都天府国际机场"],"location":"104.06,30.65"}
                """));

        assertEquals("成都东站", result.path("destinations").get(0)
                .path("candidates").get(0).path("poiname").asText());
        assertEquals(1, result.path("destinations").get(0).path("candidates").size());
        assertEquals(List.of("maps_regeocode", "maps_around_search", "maps_text_search"), calls);
    }

    @Test
    void genericTrainStationFallbackRanksLocalTextResultAndUsesCurrentCity() throws Exception {
        List<String> calls = new ArrayList<>();
        NavigationToolFacade facade = new NavigationToolFacade(tools(), (name, args) -> {
            calls.add(name);
            return switch (name) {
                case "maps_around_search" ->
                        "{\"pois\":[{\"name\":\"外地火车站\",\"location\":\"116.40,39.90\"}]}";
                case "maps_regeocode" -> "{\"city\":\"成都市\"}";
                case "maps_text_search" -> {
                    if (!args.contains("火车站")) {
                        yield "{\"pois\":[{\"name\":\"成都天府国际机场\",\"location\":\"104.44,30.31\"}]}";
                    }
                    assertTrue(args.contains("成都市"));
                    yield """
                            {"pois":[
                             {"name":"外地火车站","location":"116.40,39.90"},
                             {"name":"成都站","location":"104.08,30.69"}]}
                            """;
                }
                default -> throw new AssertionError("unexpected call: " + name);
            };
        });

        JsonNode candidate = JSON.readTree(facade.resolve("""
                {"destinations":["火车站","成都天府国际机场"],"location":"104.06,30.65"}
                """)).path("destinations").get(0).path("candidates").get(0);
        assertEquals("成都站", candidate.path("poiname").asText());
        assertEquals(List.of("maps_regeocode", "maps_around_search", "maps_text_search"),
                calls.subList(0, 3));
    }

    @Test
    void genericTrainStationWithoutLocationOrNearbyResultDoesNotOpenMultiStopRoute() throws Exception {
        NavigationToolFacade facade = new NavigationToolFacade(tools(), (name, args) ->
                "{\"pois\":[{\"name\":\"外地火车站\",\"location\":\"116.40,39.90\"}]}");
        JsonNode missingLocation = JSON.readTree(facade.resolve("""
                {"destinations":["火车站","成都天府国际机场"]}
                """));
        assertTrue(missingLocation.path("destinations").get(0).path("candidates").isEmpty());
        assertTrue(missingLocation.path("instruction").asText().contains("不得打开路线"));

        JsonNode remoteOnly = JSON.readTree(facade.resolve("""
                {"destinations":["火车站","成都天府国际机场"],"location":"104.06,30.65"}
                """));
        assertTrue(remoteOnly.path("destinations").get(0).path("candidates").isEmpty());
        assertTrue(remoteOnly.path("instruction").asText().contains("不得打开路线"));
    }

    @Test
    void laterGenericStationStillSearchesFromCurrentVehiclePosition() throws Exception {
        NavigationToolFacade facade = new NavigationToolFacade(tools(), (name, args) -> {
            if (name.equals("maps_text_search")) return """
                    {"pois":[{"name":"北京天安门","location":"116.40,39.91"}]}
                    """;
            if (name.equals("maps_regeocode")) return "{\"city\":\"成都市\"}";
            if (name.equals("maps_around_search")) {
                assertTrue(args.contains("104.06,30.65"));
                return "{\"pois\":[{\"name\":\"成都东站\",\"location\":\"104.14,30.63\"}]}";
            }
            throw new AssertionError("unexpected call: " + name);
        });

        JsonNode result = JSON.readTree(facade.resolve("""
                {"destinations":["北京天安门","火车站"],"location":"104.06,30.65"}
                """));
        assertEquals("成都东站", result.path("destinations").get(1)
                .path("candidates").get(0).path("poiname").asText());
    }

    @Test
    void railSearchLocatesFirstFiltersAccessoriesAndRanksDistance() throws Exception {
        List<String> calls = new ArrayList<>();
        NavigationToolFacade facade = new NavigationToolFacade(tools(), (name, args) -> {
            calls.add(name);
            if (name.equals("maps_regeocode")) return "{\"city\":\"成都市\"}";
            assertEquals("maps_around_search", name);
            return """
                    {"pois":[
                      {"name":"远处火车站","location":"104.8,30.65"},
                      {"name":"成都东站","location":"104.14,30.63"},
                      {"name":"成都站地铁站","location":"104.0601,30.6501"},
                      {"name":"火车站酒店","location":"104.0601,30.6501"},
                      {"name":"成都站","location":"104.08,30.69"}]}
                    """;
        });
        var result = JSON.readTree(facade.resolve("""
                {"destinations":["导航到最近的火车站"],"location":"104.06,30.65"}
                """)).path("destinations").get(0).path("candidates");
        assertEquals(List.of("maps_regeocode", "maps_around_search"), calls);
        assertEquals(2, result.size());
        assertEquals("成都站", result.get(0).path("poiname").asText());
    }

    @Test
    void railFallbackGeocodesWithResolvedCityIncludingMunicipality() throws Exception {
        var available = tools();
        available.remove("maps_around_search");
        NavigationToolFacade facade = new NavigationToolFacade(available, (name, args) -> {
            if (name.equals("maps_regeocode")) return "{\"city\":[],\"province\":\"北京市\"}";
            assertTrue(args.contains("北京市"));
            if (name.equals("maps_text_search")) return "{\"pois\":[{\"name\":\"北京南站\",\"address\":\"永外大街\"}]}";
            return "{\"geocodes\":[{\"location\":\"116.38,39.87\"}]}";
        });
        var result = JSON.readTree(facade.resolve("""
                {"destinations":["火车站"],"location":"116.4,39.9"}
                """)).path("destinations").get(0).path("candidates");
        assertEquals("北京南站", result.get(0).path("poiname").asText());
    }

    @Test
    void railFallbackWithoutCurrentCityNeverPerformsNationalSearchOrGeocode() throws Exception {
        NavigationToolFacade facade = new NavigationToolFacade(tools(), (name, args) -> {
            assertTrue(name.equals("maps_regeocode") || name.equals("maps_around_search"));
            return "{}";
        });
        var result = JSON.readTree(facade.resolve("""
                {"destinations":["火车站"],"location":"104.06,30.65"}
                """)).path("destinations").get(0).path("candidates");
        assertTrue(result.isEmpty());
    }

    @Test
    void mixedRailResultsResolveMissingCoordinatesBeforeChoosingNearest() throws Exception {
        NavigationToolFacade facade = new NavigationToolFacade(tools(), (name, args) -> switch (name) {
            case "maps_regeocode" -> "{\"city\":\"成都市\"}";
            case "maps_around_search" -> """
                    {"pois":[{"name":"成都东站","location":"104.14,30.63"},{"name":"成都站"}]}
                    """;
            case "maps_geo" -> "{\"geocodes\":[{\"location\":\"104.08,30.69\"}]}";
            default -> throw new AssertionError(name);
        });
        var result = JSON.readTree(facade.resolve("""
                {"destinations":["火车站"],"location":"104.06,30.65"}
                """)).path("destinations").get(0).path("candidates");
        assertEquals("成都站", result.get(0).path("poiname").asText());
    }

    @Test
    void failedAroundSearchUsesLocatedCityAndSchemaCompatibleStrictParameters() throws Exception {
        var available = tools();
        available.put("maps_text_search", new FunctionTool("maps_text_search", "",
                schema("keywords", "city", "citylimit", "sortrule", "offset", "types")));
        var calls = new ArrayList<String>();
        NavigationToolFacade facade = new NavigationToolFacade(available, (name, args) -> {
            calls.add(name);
            if (name.equals("maps_regeocode")) return "{\"city\":\"成都市\"}";
            if (name.equals("maps_around_search")) throw new McpToolException("unavailable");
            assertEquals("maps_text_search", name);
            assertTrue(args.contains("\"city\":\"成都市\""));
            assertTrue(args.contains("\"citylimit\":\"true\""));
            assertTrue(args.contains("\"sortrule\":\"distance\""));
            assertTrue(args.contains("\"offset\":\"25\""));
            assertTrue(args.contains("\"types\":\"150200\""));
            return "{\"pois\":[{\"name\":\"成都站\",\"location\":\"104.08,30.69\"}]}";
        });
        var result = JSON.readTree(facade.resolve("""
                {"destinations":["火车站"],"location":"104.06,30.65"}
                """)).path("destinations").get(0).path("candidates");
        assertEquals(1, result.size());
        assertEquals(List.of("maps_regeocode", "maps_around_search", "maps_text_search"), calls);
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
