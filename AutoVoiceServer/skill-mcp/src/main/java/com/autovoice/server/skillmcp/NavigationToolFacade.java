package com.autovoice.server.skillmcp;

import com.autovoice.server.contracts.FunctionTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.BiFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One model call resolving one or more spoken destinations to navigation-ready coordinates. */
final class NavigationToolFacade {
    static final String NAME = "resolve_navigation";
    private static final Logger LOG = LoggerFactory.getLogger(NavigationToolFacade.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> SOURCE_TOOLS = Set.of(
            "maps_text_search", "maps_around_search", "maps_regeocode", "maps_geo");
    private static final Pattern BROAD_AIRPORT_QUERY = Pattern.compile(
            "^(?:附近的?|周边的?|最近的?)?(?:国际)?(?:机场|飞机场)$");
    private static final Pattern NEARBY_QUERY = Pattern.compile(
            ".*(?:附近|周边|最近|就近|旁边|离我近|离当前位置近).*", Pattern.CASE_INSENSITIVE);
    private static final Pattern GENERIC_NEARBY_CATEGORY = Pattern.compile(
            "^(?:找|去|导航去|我要去)?(?:一家|一个|附近的?|周边的?|最近的?)?"
                    + "(?:咖啡(?:店|馆)?|加油站|充电站|停车场|洗车店|维修店|医院|药店|银行|"
                    + "厕所|卫生间|餐厅|饭店|酒店|宾馆|商场|超市|便利店|景区|公园|机场|飞机场)$");
    private static final Pattern LOCATION = Pattern.compile(
            "(?<![0-9.])((?:7[3-9]|[89]\\d|1[0-3]\\d|140)(?:\\.\\d+)?)\\s*,\\s*"
                    + "((?:[0-5]?\\d)(?:\\.\\d+)?)(?![0-9.])");
    /** 个人开发者基础额度通常只有 3 QPS；留出少量调度余量，避免边界抖动。 */
    private static final long GEO_MIN_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(400);
    private static final long GEO_QPS_BACKOFF_NANOS = TimeUnit.SECONDS.toNanos(1);

    static final FunctionTool TOOL = new FunctionTool(NAME,
            "一次解析一个或多个地点，返回附近优先、可直接用于 navigate 的候选坐标",
            """
            {"type":"object","properties":{
             "destinations":{"type":"array","items":{"type":"string"},"minItems":1,"maxItems":5},
             "location":{"type":"string","description":"车辆坐标 lon,lat；有定位时必填"},
             "city":{"type":"string"},"limit":{"type":"integer","minimum":1,"maximum":5}},
             "required":["destinations"]}
            """, com.autovoice.server.contracts.ToolExecutionTraits.INDEPENDENT_QUERY);

    private final Map<String, FunctionTool> tools;
    private final BiFunction<String, String, String> caller;
    private final LongSupplier nanoTime;
    private final Sleeper sleeper;
    private final Object geoRateLock = new Object();
    private long nextGeoCallNanos;

    NavigationToolFacade(McpToolSession session) {
        this(session.tools(), session::callTool);
    }

    NavigationToolFacade(Map<String, FunctionTool> tools, BiFunction<String, String, String> caller) {
        this(tools, caller, System::nanoTime, Thread::sleep);
    }

    NavigationToolFacade(Map<String, FunctionTool> tools, BiFunction<String, String, String> caller,
                         LongSupplier nanoTime, Sleeper sleeper) {
        this.tools = tools;
        this.caller = caller;
        this.nanoTime = nanoTime;
        this.sleeper = sleeper;
    }

    static boolean supports(McpToolSession session) {
        return session != null && (session.tools().containsKey("maps_around_search")
                || session.tools().containsKey("maps_text_search"))
                && session.tools().containsKey("maps_geo");
    }

    static boolean isSourceTool(String name) {
        return SOURCE_TOOLS.contains(name);
    }

    String resolve(String argumentsJson) {
        try {
            JsonNode args = JSON.readTree(argumentsJson);
            JsonNode destinations = args.path("destinations");
            if (!destinations.isArray() || destinations.isEmpty()) {
                throw new IllegalArgumentException("destinations must be a non-empty array");
            }
            String location = args.path("location").asText("");
            String city = args.path("city").asText("");
            int limit = Math.max(1, Math.min(5, args.path("limit").asInt(3)));
            boolean multiStop = destinations.size() > 1;
            ArrayNode resolved = JSON.createArrayNode();
            for (JsonNode destination : destinations) {
                if (!destination.isTextual() || destination.asText().isBlank()) continue;
                ObjectNode item = resolved.addObject();
                String query = destination.asText().trim();
                item.put("query", query);
                item.set("candidates", resolveOne(query, location, city,
                        multiStop ? 1 : limit, multiStop));
            }
            ObjectNode out = JSON.createObjectNode();
            out.set("destinations", resolved);
            out.put("instruction", multiStop
                    ? "每组已选最优结果；最后一组为目的地，其余按原顺序为 waypoints；打开路线预览，不自动开始导航"
                    : "向用户展示候选，等待下一轮确认");
            return JSON.writeValueAsString(out);
        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            throw new McpToolException("resolve_navigation failed: " + e.getMessage(), e);
        }
    }

    private ArrayNode resolveOne(String query, String location, String city, int limit,
                                 boolean forceSingleCandidate) {
        boolean broadAirportQuery = BROAD_AIRPORT_QUERY.matcher(compact(query)).matches();
        int effectiveLimit = broadAirportQuery && !forceSingleCandidate ? Math.max(limit, 5) : limit;
        int recallLimit = broadAirportQuery ? 20 : effectiveLimit;
        String searchName = !location.isBlank() && tools.containsKey("maps_around_search")
                && shouldSearchAround(query)
                ? "maps_around_search" : "maps_text_search";
        FunctionTool search = tools.get(searchName);
        if (search == null) search = tools.get("maps_text_search");
        if (search == null) throw new McpToolException("AMap search tool is unavailable");

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("keywords", query);
        values.put("keyword", query);
        values.put("query", query);
        values.put("location", location);
        values.put("center", location);
        values.put("city", city);
        values.put("radius", 50_000);
        values.put("limit", recallLimit);
        values.put("page_size", recallLimit);
        values.put("pageSize", recallLimit);
        String raw;
        try {
            raw = caller.apply(search.name(), arguments(search, values));
        } catch (McpToolException primaryError) {
            if (!"maps_around_search".equals(search.name())
                    || !tools.containsKey("maps_text_search")) {
                throw primaryError;
            }
            LOG.warn("AMap around search failed; falling back to text search: {}",
                    primaryError.getMessage());
            search = tools.get("maps_text_search");
            raw = caller.apply(search.name(), arguments(search, values));
        }
        List<Candidate> direct = candidates(raw, recallLimit);
        List<Place> places = places(raw, recallLimit);

        // 周边搜索适合“附近咖啡店”这类相对目的地，但某些返回只含名称/地址而没有坐标。
        // 先用文本搜索补一次可直接导航的坐标，避免为每个候选消耗 maps_geo 配额。
        // 这也是 named/跨城地点不应一律以车辆当前位置做周边搜索的兜底。
        if (direct.isEmpty() && "maps_around_search".equals(search.name())
                && tools.containsKey("maps_text_search")) {
            FunctionTool textSearch = tools.get("maps_text_search");
            try {
                String textRaw = caller.apply(textSearch.name(), arguments(textSearch, values));
                direct = candidates(textRaw, recallLimit);
                places = mergePlaces(places(textRaw, recallLimit), places, recallLimit);
            } catch (McpToolException error) {
                LOG.warn("AMap coordinate enrichment through text search failed; keeping around results: {}",
                        error.getMessage());
            }
        }

        if (broadAirportQuery && !location.isBlank() && tools.containsKey("maps_text_search")) {
            String resolvedCity = city.isBlank() ? reverseGeocodeCity(location) : city;
            if (!resolvedCity.isBlank()) {
                city = resolvedCity;
                FunctionTool textSearch = tools.get("maps_text_search");
                Map<String, Object> textValues = new LinkedHashMap<>(values);
                textValues.put("city", resolvedCity);
                try {
                    String textRaw = caller.apply(textSearch.name(), arguments(textSearch, textValues));
                    direct = mergeAirportCandidates(direct, candidates(textRaw, recallLimit));
                    // 高德当前 POI 搜索可能只返回名称/地址、不返回 location。城市级结果必须
                    // 一并进入后续 geocode，否则周边结果占满 limit 时会稳定漏掉较远机场。
                    places = mergePlaces(places(textRaw, recallLimit), places, recallLimit);
                } catch (McpToolException error) {
                    // Citywide enrichment is best-effort; keep nearby candidates on MCP failure.
                    LOG.warn("AMap citywide airport enrichment failed; keeping nearby results: {}",
                            error.getMessage());
                }
            }
        }
        if (!direct.isEmpty() && !broadAirportQuery) {
            return toJson(direct.stream()
                    .map(candidate -> candidate.name().isBlank()
                            ? new Candidate(query, candidate.lat(), candidate.lon(), candidate.address())
                            : candidate)
                    .toList(), limit);
        }

        if (broadAirportQuery && !direct.isEmpty()) {
            direct = mergeAirportCandidates(direct, List.of());
            Set<String> resolvedAirports = direct.stream()
                    .filter(candidate -> isRootAirport(candidate.name()))
                    .map(candidate -> airportKey(candidate.name()))
                    .collect(java.util.stream.Collectors.toSet());
            places = places.stream()
                    .filter(place -> !resolvedAirports.contains(airportKey(place.name())))
                    .toList();
            if (places.isEmpty()) return toJson(rankAirports(direct, location), effectiveLimit);
        }

        if (places.isEmpty()) places = List.of(new Place(query, query));
        if (broadAirportQuery) places = places.stream()
                .sorted(java.util.Comparator.comparingInt(place -> isRootAirport(place.name()) ? 0 : 1)).toList();
        List<Candidate> geocoded = new ArrayList<>();
        FunctionTool geo = tools.get("maps_geo");
        McpToolException firstGeoError = null;
        for (Place place : places) {
            if (broadAirportQuery && airportAccessory(place.name())) continue;
            Map<String, Object> geoValues = new LinkedHashMap<>();
            String address = String.join(" ", city, place.address(), place.name()).trim();
            geoValues.put("address", address);
            geoValues.put("city", city);
            try {
                List<Candidate> points = candidates(
                        callGeocode(geo, arguments(geo, geoValues)), 1);
                if (!points.isEmpty()) {
                    Candidate point = points.getFirst();
                    geocoded.add(new Candidate(place.name(), point.lat(), point.lon(), place.address()));
                }
            } catch (McpToolException error) {
                if (firstGeoError == null) firstGeoError = error;
                LOG.warn("AMap geocode failed for one candidate; continuing with remaining candidates: {}",
                        error.getMessage());
            }
            if (geocoded.size() >= effectiveLimit) break;
        }
        if (direct.isEmpty() && geocoded.isEmpty() && firstGeoError != null) throw firstGeoError;
        List<Candidate> result = broadAirportQuery
                ? mergeAirportCandidates(direct, geocoded) : geocoded;
        return toJson(broadAirportQuery ? rankAirports(result, location) : result, effectiveLimit);
    }

    /**
     * 地理编码是整个 Skill 会话共享的受限能力。所有并发导航请求共用一个节流门；只有高德明确
     * 返回 QPS 超限时才在本请求内退避一次，网络错误和业务错误仍立即失败。
     */
    private String callGeocode(FunctionTool geo, String argumentsJson) {
        for (int attempt = 0; attempt < 2; attempt++) {
            awaitGeoSlot();
            try {
                return caller.apply(geo.name(), argumentsJson);
            } catch (McpToolException error) {
                if (attempt > 0 || !isQpsExceeded(error)) throw error;
                deferGeoCalls(GEO_QPS_BACKOFF_NANOS);
                LOG.warn("AMap geocode QPS exceeded; retrying once after backoff");
            }
        }
        throw new IllegalStateException("unreachable");
    }

    private void awaitGeoSlot() {
        long waitNanos;
        synchronized (geoRateLock) {
            long now = nanoTime.getAsLong();
            waitNanos = Math.max(0, nextGeoCallNanos - now);
            nextGeoCallNanos = Math.max(now, nextGeoCallNanos) + GEO_MIN_INTERVAL_NANOS;
        }
        sleepNanos(waitNanos);
    }

    private void deferGeoCalls(long delayNanos) {
        synchronized (geoRateLock) {
            nextGeoCallNanos = Math.max(nextGeoCallNanos, nanoTime.getAsLong() + delayNanos);
        }
    }

    private void sleepNanos(long nanos) {
        if (nanos <= 0) return;
        try {
            sleeper.sleep(Math.max(1, TimeUnit.NANOSECONDS.toMillis(nanos)));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new McpToolException("geocode rate-limit wait interrupted", error);
        }
    }

    private static boolean isQpsExceeded(McpToolException error) {
        String message = error.getMessage();
        return message != null && (message.contains("CUQPS_HAS_EXCEEDED_THE_LIMIT")
                || message.contains("QPS_HAS_EXCEEDED_THE_LIMIT"));
    }

    private String reverseGeocodeCity(String location) {
        FunctionTool regeocode = tools.get("maps_regeocode");
        if (regeocode == null) return "";
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("location", location);
        try {
            JsonNode parsed = parseEmbeddedJson(
                    caller.apply(regeocode.name(), arguments(regeocode, values)));
            return firstTextDeep(parsed, "city");
        } catch (McpToolException ignored) {
            return "";
        }
    }

    private static List<Candidate> mergeAirportCandidates(List<Candidate> nearby,
                                                            List<Candidate> citywide) {
        LinkedHashMap<String, Candidate> merged = new LinkedHashMap<>();
        for (Candidate candidate : nearby) putPreferredAirport(merged, candidate);
        for (Candidate candidate : citywide) putPreferredAirport(merged, candidate);
        return List.copyOf(merged.values());
    }

    private static void putPreferredAirport(Map<String, Candidate> merged, Candidate candidate) {
        if (airportAccessory(candidate.name())) return;
        String key = airportKey(candidate.name());
        Candidate existing = merged.get(key);
        if (existing == null || isRootAirport(candidate.name()) && !isRootAirport(existing.name())) {
            merged.put(key, candidate);
        }
    }

    private static List<Place> mergePlaces(List<Place> preferred, List<Place> fallback, int limit) {
        LinkedHashMap<String, Place> merged = new LinkedHashMap<>();
        for (Place place : preferred) merged.putIfAbsent(compact(place.name()), place);
        for (Place place : fallback) merged.putIfAbsent(compact(place.name()), place);
        return merged.values().stream().limit(limit).toList();
    }

    private static boolean airportAccessory(String name) {
        return name.matches(".*(停车场|停车楼|酒店|宾馆|货运|航空物流|售票|办公室|公司).*");
    }

    private static List<Candidate> rankAirports(List<Candidate> candidates, String location) {
        double[] center = null;
        try {
            String[] parts = location.split(",");
            if (parts.length == 2) {
                double lon = Double.parseDouble(parts[0]), lat = Double.parseDouble(parts[1]);
                if (Double.isFinite(lon) && Double.isFinite(lat) && Math.abs(lon) <= 180 && Math.abs(lat) <= 90)
                    center = new double[]{lon, lat};
            }
        } catch (NumberFormatException ignored) { }
        final double[] origin = center;
        return candidates.stream().sorted(java.util.Comparator
                .comparingInt((Candidate point) -> isRootAirport(point.name())
                        ? (point.name().contains("国际机场") ? 0 : 1) : 2)
                .thenComparingDouble(point -> origin == null ? 0 : distanceScore(origin, point)))
                .toList();
    }

    private static double distanceScore(double[] origin, Candidate point) {
        double a = Math.toRadians(origin[1]), b = Math.toRadians(point.lat());
        return Math.pow(Math.sin((b - a) / 2), 2) + Math.cos(a) * Math.cos(b)
                * Math.pow(Math.sin(Math.toRadians(point.lon() - origin[0]) / 2), 2);
    }

    private static String airportKey(String name) {
        String value = compact(name);
        int airport = value.indexOf("机场");
        return airport < 0 ? value : value.substring(0, airport + 2);
    }

    private static boolean isRootAirport(String name) {
        return compact(name).endsWith("机场");
    }

    private static boolean shouldSearchAround(String query) {
        String value = compact(query);
        return BROAD_AIRPORT_QUERY.matcher(value).matches()
                || NEARBY_QUERY.matcher(value).matches()
                || GENERIC_NEARBY_CATEGORY.matcher(value).matches();
    }

    private static String compact(String value) {
        return value == null ? "" : value.toLowerCase().replaceAll("[\\s，。！？、,.!?;；:：()（）_-]", "");
    }

    private static String arguments(FunctionTool tool, Map<String, Object> values) {
        try {
            JsonNode properties = JSON.readTree(tool.parametersJson()).path("properties");
            ObjectNode args = JSON.createObjectNode();
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                if (!properties.isObject() || properties.has(entry.getKey())) {
                    if (entry.getValue() instanceof String text && text.isBlank()) continue;
                    args.set(entry.getKey(), JSON.valueToTree(entry.getValue()));
                }
            }
            return JSON.writeValueAsString(args);
        } catch (Exception e) {
            throw new McpToolException("cannot adapt arguments for " + tool.name(), e);
        }
    }

    private static List<Candidate> candidates(String raw, int limit) {
        List<Candidate> out = new ArrayList<>();
        JsonNode parsed = parseEmbeddedJson(raw);
        if (parsed != null) collectCandidates(parsed, "", "", out, limit);
        if (out.isEmpty()) {
            Matcher matcher = LOCATION.matcher(raw == null ? "" : raw);
            while (matcher.find() && out.size() < limit) {
                out.add(new Candidate("", Double.parseDouble(matcher.group(2)),
                        Double.parseDouble(matcher.group(1)), ""));
            }
        }
        return out;
    }

    private static void collectCandidates(JsonNode node, String inheritedName, String inheritedAddress,
                                          List<Candidate> out, int limit) {
        if (out.size() >= limit) return;
        if (node.isObject()) {
            String foundName = firstText(node, "name", "poiname", "title");
            String foundAddress = firstText(node, "address", "formatted_address", "adname");
            String name = foundName.isBlank() ? inheritedName : foundName;
            String address = foundAddress.isBlank() ? inheritedAddress : foundAddress;
            double[] point = point(node);
            if (point != null) out.add(new Candidate(name, point[1], point[0], address));
            String finalName = name;
            String finalAddress = address;
            node.elements().forEachRemaining(child ->
                    collectCandidates(child, finalName, finalAddress, out, limit));
        } else if (node.isArray()) {
            node.forEach(child -> collectCandidates(child, inheritedName, inheritedAddress, out, limit));
        }
    }

    private static double[] point(JsonNode node) {
        JsonNode location = node.path("location");
        if (location.isTextual() && location.asText().contains(",")) {
            try {
                String[] pair = location.asText().replaceAll("\\s", "").split(",");
                return new double[]{Double.parseDouble(pair[0]), Double.parseDouble(pair[1])};
            } catch (RuntimeException ignored) {
                // Try explicit fields below.
            }
        }
        JsonNode lon = node.has("lon") ? node.path("lon") : node.path("longitude");
        JsonNode lat = node.has("lat") ? node.path("lat") : node.path("latitude");
        return lon.isNumber() && lat.isNumber() ? new double[]{lon.asDouble(), lat.asDouble()} : null;
    }

    private static List<Place> places(String raw, int limit) {
        JsonNode parsed = parseEmbeddedJson(raw);
        if (parsed == null) return List.of();
        LinkedHashSet<Place> out = new LinkedHashSet<>();
        collectPlaces(parsed, out, limit);
        return out.stream().limit(limit).toList();
    }

    private static void collectPlaces(JsonNode node, Set<Place> out, int limit) {
        if (out.size() >= limit) return;
        if (node.isObject()) {
            String name = firstText(node, "name", "poiname", "title");
            String address = firstText(node, "address", "formatted_address");
            if (!name.isBlank()) out.add(new Place(name, address));
            node.elements().forEachRemaining(child -> collectPlaces(child, out, limit));
        } else if (node.isArray()) {
            node.forEach(child -> collectPlaces(child, out, limit));
        }
    }

    private static JsonNode parseEmbeddedJson(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return JSON.readTree(raw);
        } catch (Exception ignored) {
            int object = raw.indexOf('{');
            int array = raw.indexOf('[');
            int start = object < 0 ? array : array < 0 ? object : Math.min(object, array);
            int end = Math.max(raw.lastIndexOf('}'), raw.lastIndexOf(']'));
            if (start >= 0 && end > start) {
                try {
                    return JSON.readTree(raw.substring(start, end + 1));
                } catch (Exception ignoredAgain) {
                    return null;
                }
            }
            return null;
        }
    }

    private static String firstText(JsonNode node, String... names) {
        for (String name : names) {
            if (node.path(name).isValueNode() && !node.path(name).asText().isBlank()) {
                return node.path(name).asText();
            }
        }
        return "";
    }

    private static String firstTextDeep(JsonNode node, String... names) {
        if (node == null) return "";
        if (node.isObject()) {
            String direct = firstText(node, names);
            if (!direct.isBlank()) return direct;
            for (JsonNode child : node) {
                String nested = firstTextDeep(child, names);
                if (!nested.isBlank()) return nested;
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                String nested = firstTextDeep(child, names);
                if (!nested.isBlank()) return nested;
            }
        }
        return "";
    }

    private static ArrayNode toJson(List<Candidate> candidates, int limit) {
        ArrayNode out = JSON.createArrayNode();
        candidates.stream().limit(limit).forEach(candidate -> {
            ObjectNode item = out.addObject();
            item.put("poiname", candidate.name());
            item.put("lat", candidate.lat());
            item.put("lon", candidate.lon());
            if (!candidate.address().isBlank()) item.put("address", candidate.address());
        });
        return out;
    }

    private record Place(String name, String address) {
    }

    private record Candidate(String name, double lat, double lon, String address) {
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }
}
