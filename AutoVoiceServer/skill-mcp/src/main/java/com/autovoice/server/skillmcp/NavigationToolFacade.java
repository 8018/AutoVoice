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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.BiFunction;

/** One model call resolving one or more spoken destinations to navigation-ready coordinates. */
final class NavigationToolFacade {
    static final String NAME = "resolve_navigation";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> SOURCE_TOOLS = Set.of(
            "maps_text_search", "maps_around_search", "maps_regeocode", "maps_geo");
    private static final Pattern BROAD_AIRPORT_QUERY = Pattern.compile(
            "^(?:附近的?|周边的?|最近的?)?(?:国际)?(?:机场|飞机场)$");
    private static final Pattern LOCATION = Pattern.compile(
            "(?<![0-9.])((?:7[3-9]|[89]\\d|1[0-3]\\d|140)(?:\\.\\d+)?)\\s*,\\s*"
                    + "((?:[0-5]?\\d)(?:\\.\\d+)?)(?![0-9.])");

    static final FunctionTool TOOL = new FunctionTool(NAME,
            "一次解析一个或多个地点，返回附近优先、可直接用于 navigate 的候选坐标",
            """
            {"type":"object","properties":{
             "destinations":{"type":"array","items":{"type":"string"},"minItems":1,"maxItems":5},
             "location":{"type":"string","description":"车辆坐标 lon,lat；有定位时必填"},
             "city":{"type":"string"},"limit":{"type":"integer","minimum":1,"maximum":5}},
             "required":["destinations"]}
            """);

    private final Map<String, FunctionTool> tools;
    private final BiFunction<String, String, String> caller;

    NavigationToolFacade(McpToolSession session) {
        this(session.tools(), session::callTool);
    }

    NavigationToolFacade(Map<String, FunctionTool> tools, BiFunction<String, String, String> caller) {
        this.tools = tools;
        this.caller = caller;
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
            ArrayNode resolved = JSON.createArrayNode();
            for (JsonNode destination : destinations) {
                if (!destination.isTextual() || destination.asText().isBlank()) continue;
                ObjectNode item = resolved.addObject();
                String query = destination.asText().trim();
                item.put("query", query);
                item.set("candidates", resolveOne(query, location, city, limit));
            }
            ObjectNode out = JSON.createObjectNode();
            out.set("destinations", resolved);
            out.put("instruction", "按原顺序选候选；最后一组填目的地，其余填 waypoints");
            return JSON.writeValueAsString(out);
        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            throw new McpToolException("resolve_navigation failed: " + e.getMessage(), e);
        }
    }

    private ArrayNode resolveOne(String query, String location, String city, int limit) {
        boolean broadAirportQuery = BROAD_AIRPORT_QUERY.matcher(compact(query)).matches();
        int effectiveLimit = broadAirportQuery ? Math.max(limit, 5) : limit;
        String searchName = !location.isBlank() && tools.containsKey("maps_around_search")
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
        values.put("limit", effectiveLimit);
        values.put("page_size", effectiveLimit);
        values.put("pageSize", effectiveLimit);
        String raw = caller.apply(search.name(), arguments(search, values));
        List<Candidate> direct = candidates(raw, effectiveLimit);

        if (broadAirportQuery && !location.isBlank() && tools.containsKey("maps_text_search")) {
            String resolvedCity = city.isBlank() ? reverseGeocodeCity(location) : city;
            if (!resolvedCity.isBlank()) {
                FunctionTool textSearch = tools.get("maps_text_search");
                Map<String, Object> textValues = new LinkedHashMap<>(values);
                textValues.put("city", resolvedCity);
                try {
                    String textRaw = caller.apply(textSearch.name(), arguments(textSearch, textValues));
                    direct = mergeAirportCandidates(direct, candidates(textRaw, effectiveLimit));
                } catch (McpToolException ignored) {
                    // Citywide enrichment is best-effort; keep nearby candidates on MCP failure.
                }
                if (!direct.isEmpty()) return toJson(direct, effectiveLimit);
            }
        }
        if (!direct.isEmpty()) {
            return toJson(direct.stream()
                    .map(candidate -> candidate.name().isBlank()
                            ? new Candidate(query, candidate.lat(), candidate.lon(), candidate.address())
                            : candidate)
                    .toList(), limit);
        }

        List<Place> places = places(raw, effectiveLimit);
        if (places.isEmpty()) places = List.of(new Place(query, query));
        List<Candidate> geocoded = new ArrayList<>();
        FunctionTool geo = tools.get("maps_geo");
        for (Place place : places) {
            Map<String, Object> geoValues = new LinkedHashMap<>();
            String address = String.join(" ", city, place.address(), place.name()).trim();
            geoValues.put("address", address);
            geoValues.put("city", city);
            List<Candidate> points = candidates(
                    caller.apply(geo.name(), arguments(geo, geoValues)), 1);
            if (!points.isEmpty()) {
                Candidate point = points.getFirst();
                geocoded.add(new Candidate(place.name(), point.lat(), point.lon(), place.address()));
            }
            if (geocoded.size() >= effectiveLimit) break;
        }
        return toJson(geocoded, effectiveLimit);
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
        String key = airportKey(candidate.name());
        Candidate existing = merged.get(key);
        if (existing == null || isRootAirport(candidate.name()) && !isRootAirport(existing.name())) {
            merged.put(key, candidate);
        }
    }

    private static String airportKey(String name) {
        String value = compact(name);
        int airport = value.indexOf("机场");
        return airport < 0 ? value : value.substring(0, airport + 2);
    }

    private static boolean isRootAirport(String name) {
        return compact(name).endsWith("机场");
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
}
