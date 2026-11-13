package com.autovoice.server.skillmcp;

import com.autovoice.server.contracts.FunctionTool;
import com.autovoice.server.contracts.NavigationRoutePreview.Preference;
import com.autovoice.server.skillmcp.NavigationPlaceConstraintService.Constraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One model call resolving one or more spoken destinations to navigation-ready coordinates. */
final class NavigationToolFacade {
    static final String NAME = "resolve_navigation";
    private static final Logger LOG = LoggerFactory.getLogger(NavigationToolFacade.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> SOURCE_TOOLS = Set.of(
            "maps_text_search", "maps_around_search", "maps_regeocode", "maps_geo", "maps_search_detail");
    private static final Pattern BROAD_AIRPORT_QUERY = Pattern.compile(
            "^(?:附近的?|周边的?|最近的?)?(?:国际)?(?:机场|飞机场)$");
    private static final Pattern NAMED_AIRPORT_QUERY = Pattern.compile("^[\\p{IsHan}]{2,}机场$");
    private static final Pattern NEARBY_QUERY = Pattern.compile(
            ".*(?:附近|周边|最近|就近|旁边|离我近|离当前位置近).*", Pattern.CASE_INSENSITIVE);
    private static final Pattern GENERIC_NEARBY_CATEGORY = Pattern.compile(
            "^(?:找|去|导航去|我要去)?(?:一家|一个|附近的?|周边的?|最近的?)?"
                    + "(?:咖啡(?:店|馆|厅)?|博物馆|科技馆|门店|加油站|充电站|停车场|洗车店|维修店|医院|药店|银行|"
                    + "厕所|卫生间|餐厅|饭店|酒店|宾馆|商场|超市|便利店|景区|公园|机场|飞机场)$");
    private static final Pattern GENERIC_RAIL_STATION = Pattern.compile(
            "^(?:请|帮我|带我|我要)?(?:导航到|导航去|导航|去|找)?(?:离我最近的?|附近的?|周边的?|最近的?|就近的?)?(?:火车站|高铁站|动车站|铁路站)$");
    private static final Pattern DESTINATION_PREFIX = Pattern.compile(
            "^(?:请)?(?:帮我)?(?:带我去|导航到|导航去|搜索|查找|找|去)(?=.{2,})");
    private static final Pattern TERMINAL_NUMBER = Pattern.compile(
            "(?:t([1-9]\\d*)|([一二三四五六七八九1-9][0-9]*)号?航站楼)");
    private static final Pattern REFERENCE_QUERY = Pattern.compile(
            "^(.+?)(?:附近|周边|旁边)(?:的)?(.+)$");
    private static final Pattern CITY_PREFIX = Pattern.compile(
            "^(北京|上海|天津|重庆)(?:市|的)?(.+)$|^([\\p{IsHan}]{2,10}市)(?:的)?(.+)$"
            + "|^(杭州|成都|广州|深圳|南京|武汉|西安|长沙|郑州|济南|苏州|宁波|厦门|青岛)的(.+)$");
    private static final Pattern ENTRANCE = Pattern.compile(
            "(?:t[1-9]\\d*|[一二三四五六七八九\\d]+号?(?:航站楼|出入口|出口|入口|门)|[东西南北](?:侧)?(?:出入口|出口|入口|门)|航站楼|出入口|出口|入口)");
    private static final Pattern STREET_ADDRESS = Pattern.compile(
            ".*(?:路|街|道|巷|弄).*[0-9零一二三四五六七八九十百]+号(?:楼)?$");
    private static final int SEARCH_RECALL_LIMIT = 20;
    private static final int DETAIL_RECALL_LIMIT = 5;
    private static final int RAIL_RECALL_LIMIT = 25;
    private static final double MAX_GENERIC_RAIL_DISTANCE_KM = 50;
    private static final int REFERENCE_RADIUS_METERS = 5_000;
    private static final int VEHICLE_RADIUS_METERS = 50_000;
    private static final Pattern LOCATION = Pattern.compile(
            "(?<![0-9.])((?:7[3-9]|[89]\\d|1[0-3]\\d|140)(?:\\.\\d+)?)\\s*,\\s*"
                    + "((?:[0-5]?\\d)(?:\\.\\d+)?)(?![0-9.])");
    /** 个人开发者基础额度通常只有 3 QPS；留出少量调度余量，避免边界抖动。 */
    private static final long POI_MIN_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(400);
    private static final long POI_QPS_BACKOFF_NANOS = TimeUnit.SECONDS.toNanos(1);

    static final FunctionTool TOOL = new FunctionTool(NAME,
            "解析地点和明确的搜索范围；每站展示最多三个候选。多地点必须按原顺序完整传入，家和公司分别使用“家”和“公司”。保留所有明确条件。不要把未知营业或空闲状态说成已验证。",
            """
            {"type":"object","properties":{
             "destinations":{"type":"array","description":"保留完整地点专名和分馆，如北京南站、上海博物馆(东馆)、上海虹桥机场；不能因 city 已填写而删去名称内的城市。上海的博物馆才是类别检索。","items":{"type":"string"},"minItems":1,"maxItems":5},
             "location":{"type":"string","description":"车辆坐标 lon,lat；有定位时必填"},
             "city":{"type":"string","description":"填写用户说出的城市，包括连在机场等地点名称前的城市；不要填车辆定位城市"},
             "district":{"type":"string","description":"用户明确指定的区县"},
             "reference":{"type":"string","description":"用户要求在其周边检索的参考地点，destinations 只放要找的类别"},
             "entrance":{"type":"string","description":"用户明确要求的入口或航站楼，例如 T2 或东门"},
             "constraints":{"type":"array","minItems":1,"maxItems":5,
               "description":"逐站对应 destinations，没有条件的站用空对象；地点只放查找目标，条件放这里。具体别名用 alias，地标特征用 feature。",
               "items":{"type":"object","additionalProperties":false,"properties":{
                 "brand":{"type":"string","maxLength":80,"description":"用户明确指定的品牌名"},
                 "category":{"type":"string","maxLength":80,"description":"用户明确指定的地图分类名，如咖啡厅"},
                 "alias":{"type":"string","maxLength":80,"description":"用户说出的具体别名，须与地图登记的别名相符"},
                 "feature":{"type":"string","maxLength":80,"description":"待查证的描述性特征，如有摩天轮、适合带孩子、快充、桩空闲"},
                 "openNow":{"type":"boolean","description":"用户明确要求当前营业时填 true"}}}},
             "chargingPlan":{"type":"object","additionalProperties":false,
               "description":"要求顺路且适配、到达保留电量或组合充电与休息时必填。单纯查附近充电站不用填。",
               "properties":{"mode":{"type":"string","enum":["along_route_compatible","arrival_soc","charge_and_rest"]},
                 "minimumArrivalSoc":{"type":"number","minimum":0,"maximum":100,"description":"用户明确说的到达时最低剩余电量百分数"}},
               "required":["mode"]},
             "routePreviewPreference":{"type":"string","enum":["DEFAULT","AVOID_CONGESTION","PREFER_HIGHWAYS","AVOID_HIGHWAYS","LESS_TOLLS","FASTEST"],
               "description":"仅在单目的地只读查路线、时间、距离、比较或明确路线偏好时必填，无明确偏好填 DEFAULT；地点选择后显示路线预览"},
             "limit":{"type":"integer","minimum":1,"maximum":3}},
             "required":["destinations"]}
            """, com.autovoice.server.contracts.ToolExecutionTraits.INDEPENDENT_QUERY);

    private final Map<String, FunctionTool> tools;
    private final BiFunction<String, String, String> caller;
    private final LongSupplier nanoTime;
    private final Sleeper sleeper;
    private final Object poiRateLock = new Object();
    private long nextPoiCallNanos;

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
            if (args.has("chargingPlan")) return blocked(ChargingTripPlanner.unavailableReason(args.path("chargingPlan")));
            JsonNode destinations = args.path("destinations");
            if (!destinations.isArray() || destinations.isEmpty()) {
                throw new IllegalArgumentException("destinations must contain place names");
            }
            if (destinations.size() > 5) {
                return JSON.createObjectNode().put("message", "一次最多安排5个地点，请减少地点后重试。").toString();
            }
            for (JsonNode destination : destinations) {
                if (!destination.isTextual() || destination.asText().isBlank()
                        || destination.asText().length() > 256) {
                    throw new IllegalArgumentException("each destination must be a valid place name");
                }
            }
            JsonNode requestedConstraints = args.path("constraints");
            List<Constraints> constraints = new ArrayList<>();
            try {
                if (!requestedConstraints.isMissingNode() && (!requestedConstraints.isArray()
                        || requestedConstraints.size() != destinations.size())) {
                    return blocked("请分别说明每个地点需要满足的条件。");
                }
                for (int i = 0; i < destinations.size(); i++) {
                    constraints.add(Constraints.parse(requestedConstraints.path(i)));
                }
            } catch (IllegalArgumentException e) {
                return blocked(e.getMessage());
            }
            List<String> unsupported = constraints.stream().flatMap(value -> value.unsupported().stream())
                    .distinct().toList();
            if (!unsupported.isEmpty()) {
                return JSON.createObjectNode().put("blocked", true)
                        .put("message", "地图无法核验“" + String.join("、", unsupported)
                                + "”。请补充地点名称，或说明可以放宽哪些条件。")
                        .set("unknownConditions", JSON.valueToTree(unsupported)).toString();
            }
            Preference previewPreference = null;
            if (args.has("routePreviewPreference")) {
                try {
                    previewPreference = Preference.valueOf(args.path("routePreviewPreference").asText());
                } catch (IllegalArgumentException e) {
                    return blocked("请说要比较的路线偏好。");
                }
                if (destinations.size() != 1) {
                    return blocked("请先选择一个目的地预览路线。");
                }
            }
            String location = args.path("location").asText("");
            if (parseLocation(location) == null) location = "";
            int limit = Math.max(1, Math.min(3, args.path("limit").asInt(3)));
            boolean multiStop = destinations.size() > 1;
            if (multiStop) limit = 3;
            Set<CandidateKey> excluded = candidateKeys(args.path("excluded"));
            ObjectNode out = JSON.createObjectNode();
            if (previewPreference != null) out.put("routePreviewPreference", previewPreference.name());
            ArrayNode resolved = out.putArray("destinations");
            for (int i = 0; i < destinations.size(); i++) {
                JsonNode destination = destinations.get(i);
                Constraints conditions = constraints.get(i);
                String savedPlace = multiStop ? savedPlace(destination.asText()) : "";
                if (!savedPlace.isBlank() && conditions.active()) {
                    return blocked(NavigationPlaceConstraintService.UNVERIFIED);
                }
                if (!savedPlace.isBlank()) {
                    resolved.addObject().put("query", destination.asText().strip()).put("savedPlace", savedPlace);
                    continue;
                }
                Search search = Search.parse(destination.asText(), args, conditions);
                ObjectNode item = resolved.addObject().put("query", search.query());
                ArrayNode matches;
                if (location.isBlank() && search.city().isBlank() && search.reference().isBlank()
                        && !search.isNamedAirport()) {
                    matches = JSON.createArrayNode();
                    out.put("message", "请说要查找的城市。");
                } else if (!search.reference().isBlank()) {
                    String center = referenceCenter(search);
                    if (center.isBlank()) {
                        matches = JSON.createArrayNode();
                        out.put("message", "请说更具体的参考地点名称或地址。");
                    } else {
                        matches = resolveOne(search, center, limit, excluded, conditions);
                    }
                } else {
                    matches = resolveOne(search, location, limit, excluded, conditions);
                }
                item.set("candidates", matches);
                if (!multiStop) {
                    ObjectNode searchState = search.toJson();
                    if (conditions.active()) searchState.set("constraints", requestedConstraints);
                    if (previewPreference != null) searchState.put("routePreviewPreference", previewPreference.name());
                    out.set("search", searchState);
                }
                if (!matches.isEmpty() && search.isChargingStation()) {
                    out.put("availabilityNotice", "充电桩空闲状态未知。");
                }
                if (matches.isEmpty() && conditions.active()) {
                    out.put("blocked", true);
                    if (!out.has("message")) {
                        out.put("message", excluded.isEmpty() ? NavigationPlaceConstraintService.UNVERIFIED
                                : "没有找到更多符合原有条件的候选。");
                    }
                }
                if (matches.isEmpty() && !out.has("message")) {
                    if (!excluded.isEmpty()) out.put("message", "没有找到更多候选。");
                    else if (!search.entrance().isBlank()) out.put("message", "未找到指定入口，请说完整的入口名称。");
                    else if (STREET_ADDRESS.matcher(search.query()).matches()) out.put("message", "未找到这个门牌，请确认完整地址。");
                    else out.put("message", "未找到可靠地点，请说更具体的地点名称。");
                }
            }
            out.put("instruction", out.has("message")
                    ? (multiStop ? "不得打开路线。" : "") + out.path("message").asText()
                    : multiStop
                        ? "保留每站候选及原有顺序，生成行程草稿并等待用户确认"
                        : "向用户展示候选，等待下一轮确认");
            return JSON.writeValueAsString(out);
        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            throw new McpToolException("resolve_navigation failed: " + e.getMessage(), e);
        }
    }

    private static String blocked(String message) {
        return JSON.createObjectNode().put("blocked", true).put("message", message).toString();
    }

    private static String savedPlace(String query) {
        return switch (query.strip()) {
            case "家", "我家", "回家", "家庭地址" -> "home";
            case "公司", "我的公司", "去公司", "上班" -> "work";
            default -> "";
        };
    }

    private ArrayNode resolveOne(Search criteria, String location, int limit,
                                 Set<CandidateKey> excluded, Constraints constraints) {
        String query = criteria.query();
        String city = criteria.city();
        boolean referenced = !criteria.reference().isBlank();
        boolean cityScoped = !city.isBlank() || !criteria.district().isBlank();
        boolean broadAirportQuery = BROAD_AIRPORT_QUERY.matcher(compact(query)).matches()
                && criteria.entrance().isBlank();
        boolean genericRailStation = GENERIC_RAIL_STATION.matcher(compact(query)).matches();
        if (genericRailStation && !cityScoped && !referenced && !constraints.active()) {
            return resolveRailStation(location, limit, excluded);
        }
        int recallLimit = SEARCH_RECALL_LIMIT;
        boolean nearby = referenced || !cityScoped && shouldSearchAround(query);
        String searchName = nearby && !location.isBlank() && tools.containsKey("maps_around_search")
                ? "maps_around_search" : "maps_text_search";
        if (referenced && !"maps_around_search".equals(searchName)) {
            return JSON.createArrayNode();
        }
        FunctionTool search = tools.get(searchName);
        if (search == null) search = tools.get("maps_text_search");
        if (search == null) throw new McpToolException("AMap search tool is unavailable");

        Map<String, Object> values = new LinkedHashMap<>();
        String keywords = constraints.keywords(criteria.keywords());
        values.put("keywords", keywords);
        values.put("keyword", keywords);
        values.put("query", keywords);
        values.put("location", cityScoped && !referenced ? "" : location);
        values.put("center", cityScoped && !referenced ? "" : location);
        values.put("city", city);
        values.put("citylimit", !city.isBlank());
        values.put("offset", recallLimit);
        int radius = referenced ? REFERENCE_RADIUS_METERS : VEHICLE_RADIUS_METERS;
        double[] center = nearby ? parseLocation(location) : null;
        values.put("radius", radius);
        values.put("sortrule", "distance");
        values.put("limit", recallLimit);
        values.put("page_size", recallLimit);
        values.put("pageSize", recallLimit);
        String raw;
        try {
            raw = caller.apply(search.name(), arguments(search, values));
        } catch (McpToolException primaryError) {
            if (referenced || !"maps_around_search".equals(search.name())
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
        if (direct.isEmpty() && !referenced
                && "maps_around_search".equals(search.name())
                && tools.containsKey("maps_text_search")) {
            FunctionTool textSearch = tools.get("maps_text_search");
            try {
                Map<String, Object> textValues = new LinkedHashMap<>(values);
                String textRaw = caller.apply(textSearch.name(), arguments(textSearch, textValues));
                List<Candidate> textCandidates = candidates(textRaw, recallLimit);
                direct = textCandidates;
                places = mergePlaces(places(textRaw, recallLimit), places, recallLimit);
            } catch (McpToolException error) {
                LOG.warn("AMap coordinate enrichment through text search failed; keeping around results: {}",
                        error.getMessage());
            }
        }

        if (broadAirportQuery && !cityScoped && !referenced
                && !location.isBlank() && tools.containsKey("maps_text_search")) {
            String resolvedCity = city.isBlank() ? reverseGeocodeCity(location) : city;
            if (!resolvedCity.isBlank()) {
                city = resolvedCity;
                FunctionTool textSearch = tools.get("maps_text_search");
                Map<String, Object> textValues = new LinkedHashMap<>(values);
                textValues.put("city", resolvedCity);
                textValues.put("citylimit", true);
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
        direct = direct.stream().filter(NavigationToolFacade::validCoordinates)
                .filter(candidate -> constraints.matches(candidate.name(), candidate.category(), candidate.alias()))
                .filter(candidate -> !candidate.name().isBlank())
                .filter(candidate -> criteria.matches(candidate.name(), candidate.address(),
                        candidate.city(), candidate.district(), candidate.alias()))
                .filter(candidate -> broadAirportQuery && !referenced || withinRadius(center, candidate, radius))
                .toList();
        if (tools.containsKey("maps_search_detail") && (constraints.active() || !criteria.entrance().isBlank()
                || criteria.isNamedAirport() || criteria.isExactPlace() || criteria.isChargingStation())) {
            long resolvedCount = direct.stream().map(CandidateKey::from)
                    .filter(key -> !excluded.contains(key)).distinct().limit(limit).count();
            int remaining = limit - (int) resolvedCount;
            if (remaining > 0) {
                List<Candidate> enriched = verifiedDetails(places, constraints, criteria, center, radius, remaining,
                        excluded, direct);
                if (!enriched.isEmpty()) {
                    direct = new ArrayList<>(direct);
                    direct.addAll(enriched);
                }
            }
        }
        if (constraints.active()) {
            return toJson(broadAirportQuery ? rankAirports(direct, location) : direct, limit, excluded, constraints);
        }
        places = places.stream()
                .filter(place -> criteria.matches(place.name(), place.address(), place.city(), place.district()))
                .toList();
        if (!direct.isEmpty() && !broadAirportQuery) {
            return toJson(direct, limit, excluded, constraints);
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
            if (places.isEmpty()) return toJson(rankAirports(direct, location), limit, excluded, constraints);
        }

        if (places.isEmpty()) {
            if (!criteria.entrance().isBlank() || nearby || !criteria.district().isBlank()
                    || criteria.isStreetAddress() || criteria.isChargingStation() || criteria.isNamedAirport() || criteria.isExactPlace()) {
                return JSON.createArrayNode();
            }
            places = List.of(new Place(query, query));
        }
        if (broadAirportQuery) places = places.stream()
                .sorted(java.util.Comparator.comparingInt(place -> isRootAirport(place.name()) ? 0 : 1)).toList();
        List<Candidate> geocoded = new ArrayList<>();
        FunctionTool geo = tools.get("maps_geo");
        McpToolException firstGeoError = null;
        if (geo == null) return toJson(direct, limit, excluded, constraints);
        for (Place place : places) {
            if (broadAirportQuery && airportAccessory(place.name())) continue;
            Map<String, Object> geoValues = new LinkedHashMap<>();
            String placeCity = city.isBlank() ? place.city() : city;
            String address = String.join(" ", placeCity, place.address(), place.name()).trim();
            geoValues.put("address", address);
            geoValues.put("city", placeCity);
            try {
                List<Candidate> points = candidates(
                        callRateLimitedPoiTool(geo, arguments(geo, geoValues)), 1);
                if (!points.isEmpty()) {
                    Candidate point = points.getFirst();
                    if (!criteria.acceptsGeocode(point)) continue;
                    Candidate candidate = new Candidate(place.name(), point.lat(), point.lon(), place.address());
                    if (validCoordinates(candidate)
                            && (broadAirportQuery && !referenced || withinRadius(center, candidate, radius))
                            && !excluded.contains(CandidateKey.from(candidate)) && !geocoded.contains(candidate)) {
                        geocoded.add(candidate);
                    }
                }
            } catch (McpToolException error) {
                if (firstGeoError == null) firstGeoError = error;
                LOG.warn("AMap geocode failed for one candidate; continuing with remaining candidates: {}",
                        error.getMessage());
            }
            if (geocoded.size() >= limit) break;
        }
        if (direct.isEmpty() && geocoded.isEmpty() && firstGeoError != null) throw firstGeoError;
        List<Candidate> result = broadAirportQuery
                ? mergeAirportCandidates(direct, geocoded) : geocoded;
        return toJson(broadAirportQuery ? rankAirports(result, location)
                : result, limit, excluded, constraints);
    }

    private List<Candidate> verifiedDetails(List<Place> places, Constraints constraints,
                                            Search search, double[] center, int radius, int limit,
                                            Set<CandidateKey> excluded, List<Candidate> direct) {
        Set<CandidateKey> seen = new LinkedHashSet<>(excluded);
        for (Candidate candidate : direct) seen.add(CandidateKey.from(candidate));
        List<Candidate> matches = new ArrayList<>();
        Set<String> queriedIds = new LinkedHashSet<>();
        for (Place place : places) {
            if (queriedIds.size() == DETAIL_RECALL_LIMIT || matches.size() == limit) break;
            if (place.id().isBlank() || !constraints.matchesBrand(place.name())
                    || !search.canEnrich(place)
                    || !queriedIds.add(place.id())) continue;
            try {
                Candidate candidate = poiDetail(place);
                if (candidate != null && withinRadius(center, candidate, radius)
                        && constraints.matches(candidate.name(), candidate.category(), candidate.alias())
                        && search.matches(candidate.name(), candidate.address(), candidate.city(), candidate.district(), candidate.alias())
                        && seen.add(CandidateKey.from(candidate))) {
                    matches.add(candidate);
                }
            } catch (McpToolException error) {
                if (Thread.currentThread().isInterrupted()) throw error;
                LOG.warn("AMap place detail could not be verified: {}", error.getClass().getSimpleName());
            }
        }
        return matches;
    }

    private Candidate poiDetail(Place place) {
        FunctionTool detail = tools.get("maps_search_detail");
        JsonNode raw = parseEmbeddedJson(callRateLimitedPoiTool(detail, arguments(detail, Map.of("id", place.id()))));
        JsonNode poi = findPoiById(raw, place.id());
        if (poi == null) return null;
        double[] coordinates = point(poi);
        if (coordinates == null) return null;
        String name = firstText(poi, "name");
        String address = firstText(poi, "address");
        String city = firstText(poi, "city", "cityname");
        String district = firstText(poi, "adname", "district");
        Candidate candidate = new Candidate(name.isBlank() ? place.name() : name, coordinates[1], coordinates[0],
                address.isBlank() ? place.address() : address,
                city.isBlank() ? place.city() : city,
                district.isBlank() ? place.district() : district,
                firstText(poi, "type"), firstText(poi, "alias"), Instant.now());
        return validCoordinates(candidate) ? candidate : null;
    }

    private static JsonNode findPoiById(JsonNode node, String id) {
        if (node == null) return null;
        if (node.isObject() && id.equals(firstText(node, "id"))) return node;
        if (node.isContainerNode()) {
            for (JsonNode child : node) {
                JsonNode match = findPoiById(child, id);
                if (match != null) return match;
            }
        }
        return null;
    }

    /** Resolve current city before any category search; never use an unbounded national fallback. */
    private ArrayNode resolveRailStation(String location, int limit, Set<CandidateKey> excluded) {
        double[] origin = parseLocation(location);
        if (origin == null) return JSON.createArrayNode();
        String city = reverseGeocodeCity(location);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("keywords", "火车站");
        values.put("keyword", "火车站");
        values.put("query", "火车站");
        values.put("types", "150200");
        values.put("location", location);
        values.put("center", location);
        values.put("city", city);
        values.put("citylimit", true);
        values.put("radius", 50_000);
        values.put("sortrule", "distance");
        values.put("special", false);
        values.put("offset", RAIL_RECALL_LIMIT);
        values.put("limit", RAIL_RECALL_LIMIT);
        values.put("page_size", RAIL_RECALL_LIMIT);
        values.put("pageSize", RAIL_RECALL_LIMIT);

        List<Candidate> direct = new ArrayList<>();
        List<Place> unresolved = new ArrayList<>();
        FunctionTool around = tools.get("maps_around_search");
        if (around != null) {
            try {
                String raw = caller.apply(around.name(), arguments(around, values));
                direct.addAll(candidates(raw, RAIL_RECALL_LIMIT));
                unresolved.addAll(places(raw, RAIL_RECALL_LIMIT));
            } catch (McpToolException error) {
                LOG.warn("Rail nearby search failed; trying current-city search: {}", error.getMessage());
            }
        }
        // Some MCP tools omit coordinates or ignore distance order. Retain the city for both
        // text fallback and all geocodes; a model-supplied city is never a current-position fix.
        if (nearbyRailCandidates(direct, origin).isEmpty() && !city.isBlank()
                && tools.containsKey("maps_text_search")) {
            FunctionTool search = tools.get("maps_text_search");
            try {
                String raw = caller.apply(search.name(), arguments(search, values));
                direct.addAll(candidates(raw, RAIL_RECALL_LIMIT));
                unresolved = mergePlaces(places(raw, RAIL_RECALL_LIMIT), unresolved, RAIL_RECALL_LIMIT);
            } catch (McpToolException error) {
                LOG.warn("Rail current-city search failed: {}", error.getMessage());
            }
        }
        // Fill missing station coordinates before sorting, even if another station already
        // has coordinates. Otherwise a nearer station can be silently lost.
        if (!city.isBlank() && tools.containsKey("maps_geo")) {
            Set<String> known = direct.stream().map(Candidate::name).collect(java.util.stream.Collectors.toSet());
            for (Place place : unresolved) {
                if (!railStationName(place.name()) || known.contains(place.name())) continue;
                Map<String, Object> geoValues = Map.of("city", city,
                        "address", String.join(" ", city, place.address(), place.name()).trim());
                try {
                    List<Candidate> points = candidates(callRateLimitedPoiTool(tools.get("maps_geo"),
                            arguments(tools.get("maps_geo"), geoValues)), 1);
                    if (!points.isEmpty()) {
                        Candidate point = points.getFirst();
                        direct.add(new Candidate(place.name(), point.lat(), point.lon(), place.address()));
                    }
                } catch (McpToolException error) {
                    LOG.warn("Rail coordinate enrichment failed: {}", error.getMessage());
                }
            }
        }
        return toJson(nearbyRailCandidates(direct, origin), limit, excluded, Constraints.NONE);
    }

    private static boolean railStationName(String name) {
        String value = compact(name);
        return value.endsWith("站") && !value.matches(".*(地铁|公交|货运|货场|售票|停车|酒店|宾馆|广场|商店|汽车站|客运站).*");
    }

    /**
     * 地理编码和地点详情请求共用整个 Skill 会话的节流门。只有高德明确
     * 返回 QPS 超限时才在本请求内退避一次，网络错误和业务错误仍立即失败。
     */
    private String callRateLimitedPoiTool(FunctionTool tool, String argumentsJson) {
        for (int attempt = 0; attempt < 2; attempt++) {
            awaitPoiSlot();
            try {
                return caller.apply(tool.name(), argumentsJson);
            } catch (McpToolException error) {
                if (attempt > 0 || !isQpsExceeded(error)) throw error;
                deferPoiCalls(POI_QPS_BACKOFF_NANOS);
                LOG.warn("AMap POI tool QPS exceeded; retrying once after backoff");
            }
        }
        throw new IllegalStateException("unreachable");
    }

    private void awaitPoiSlot() {
        long waitNanos;
        synchronized (poiRateLock) {
            long now = nanoTime.getAsLong();
            waitNanos = Math.max(0, nextPoiCallNanos - now);
            nextPoiCallNanos = Math.max(now, nextPoiCallNanos) + POI_MIN_INTERVAL_NANOS;
        }
        sleepNanos(waitNanos);
    }

    private void deferPoiCalls(long delayNanos) {
        synchronized (poiRateLock) {
            nextPoiCallNanos = Math.max(nextPoiCallNanos, nanoTime.getAsLong() + delayNanos);
        }
    }

    private void sleepNanos(long nanos) {
        if (nanos <= 0) return;
        try {
            sleeper.sleep(Math.max(1, TimeUnit.NANOSECONDS.toMillis(nanos)));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new McpToolException("AMap POI tool rate-limit wait interrupted", error);
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
            String city = firstTextDeep(parsed, "city");
            if (!city.isBlank()) return city;
            // AMap returns city:[] for municipalities; province then names the city.
            String province = firstTextDeep(parsed, "province");
            return Set.of("北京市", "上海市", "天津市", "重庆市").contains(province) ? province : "";
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
        for (Place place : preferred) merged.putIfAbsent(place.key(), place);
        for (Place place : fallback) merged.putIfAbsent(place.key(), place);
        return merged.values().stream().limit(limit).toList();
    }

    private static boolean airportAccessory(String name) {
        return name.matches(".*(停车场|停车楼|酒店|宾馆|货运|航空物流|售票|办公室|公司).*");
    }

    private static List<Candidate> rankAirports(List<Candidate> candidates, String location) {
        final double[] origin = parseLocation(location);
        return candidates.stream().sorted(java.util.Comparator
                .comparingInt((Candidate point) -> isRootAirport(point.name())
                        ? (point.name().contains("国际机场") ? 0 : 1) : 2)
                .thenComparingDouble(point -> origin == null ? 0 : distanceScore(origin, point)))
                .toList();
    }

    private static double[] parseLocation(String location) {
        try {
            String[] parts = location.split(",");
            if (parts.length == 2) {
                double lon = Double.parseDouble(parts[0]), lat = Double.parseDouble(parts[1]);
                if (Double.isFinite(lon) && Double.isFinite(lat) && Math.abs(lon) <= 180 && Math.abs(lat) <= 90)
                    return new double[]{lon, lat};
            }
        } catch (NumberFormatException ignored) { }
        return null;
    }

    private static List<Candidate> nearbyRailCandidates(List<Candidate> candidates, double[] origin) {
        if (origin == null) return List.of();
        return candidates.stream()
                .filter(point -> railStationName(point.name()))
                .distinct()
                .filter(point -> Double.isFinite(point.lat()) && Double.isFinite(point.lon())
                        && Math.abs(point.lat()) <= 90 && Math.abs(point.lon()) <= 180)
                .filter(point -> 2 * 6371 * Math.asin(Math.min(1, Math.sqrt(distanceScore(origin, point))))
                        <= MAX_GENERIC_RAIL_DISTANCE_KM)
                .sorted(java.util.Comparator.comparingDouble(point -> distanceScore(origin, point)))
                .toList();
    }

    private static boolean withinRadius(double[] origin, Candidate point, int radiusMeters) {
        return origin == null || 2 * 6_371_000 * Math.asin(Math.min(1, Math.sqrt(distanceScore(origin, point))))
                <= radiusMeters;
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
                || GENERIC_RAIL_STATION.matcher(value).matches()
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
                    if ("string".equals(properties.path(entry.getKey()).path("type").asText())) {
                        args.put(entry.getKey(), String.valueOf(entry.getValue()));
                    } else {
                        args.set(entry.getKey(), JSON.valueToTree(entry.getValue()));
                    }
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
        if (parsed != null) collectCandidates(parsed, "", "", out, limit, Instant.now());
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
                                          List<Candidate> out, int limit, Instant retrievedAt) {
        if (out.size() >= limit) return;
        if (node.isObject()) {
            String foundName = firstText(node, "name", "poiname", "title");
            String foundAddress = firstText(node, "address", "formatted_address", "adname");
            String name = foundName.isBlank() ? inheritedName : foundName;
            String address = foundAddress.isBlank() ? inheritedAddress : foundAddress;
            double[] point = point(node);
            if (point != null) {
                out.add(new Candidate(name, point[1], point[0], address,
                        firstText(node, "cityname", "city"), firstText(node, "adname", "district"),
                        foundName.isBlank() ? "" : firstText(node, "type"),
                        foundName.isBlank() ? "" : firstText(node, "alias"), retrievedAt));
            }
            String finalName = name;
            String finalAddress = address;
            node.elements().forEachRemaining(child ->
                    collectCandidates(child, finalName, finalAddress, out, limit, retrievedAt));
        } else if (node.isArray()) {
            node.forEach(child -> collectCandidates(child, inheritedName, inheritedAddress, out, limit, retrievedAt));
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
            if (!name.isBlank()) {
                out.add(new Place(name, address, firstText(node, "cityname", "city"),
                        firstText(node, "adname", "district"), firstText(node, "id"), firstText(node, "alias")));
            }
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
            if (node.path(name).isValueNode() && !node.path(name).isNull() && !node.path(name).asText().isBlank()) {
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

    private String referenceCenter(Search criteria) {
        FunctionTool search = tools.get("maps_text_search");
        if (search == null) return "";
        String city = criteria.city();
        String keywords = (criteria.district() + " " + criteria.reference()).strip();
        Map<String, Object> values = Map.of("keywords", keywords, "keyword", keywords,
                "query", keywords, "city", city, "citylimit", !city.isBlank(),
                "limit", SEARCH_RECALL_LIMIT, "offset", SEARCH_RECALL_LIMIT);
        String raw = caller.apply(search.name(), arguments(search, values));
        var exact = candidates(raw, SEARCH_RECALL_LIMIT).stream()
                .filter(NavigationToolFacade::validCoordinates)
                .filter(candidate -> criteria.matchesReference(candidate.name(), candidate.address()))
                .filter(candidate -> criteria.matchesArea(candidate.name(), candidate.address(),
                        candidate.city(), candidate.district()))
                .map(candidate -> candidate.lon() + "," + candidate.lat()).distinct().toList();
        var landmarks = places(raw, SEARCH_RECALL_LIMIT).stream()
                .filter(place -> criteria.matchesReference(place.name(), place.address()))
                .filter(place -> criteria.matchesArea(place.name(), place.address(), place.city(), place.district()))
                .toList();
        if (exact.size() > 1 || landmarks.size() > 1) return "";
        if (exact.size() == 1) return exact.getFirst();
        if (landmarks.size() != 1) return "";

        Place place = landmarks.getFirst();
        if (!place.id().isBlank() && tools.containsKey("maps_search_detail")) {
            try {
                Candidate detail = poiDetail(place);
                if (detail != null && criteria.matchesReference(detail.name(), detail.address())
                        && criteria.matchesArea(detail.name(), detail.address(), detail.city(), detail.district())) {
                    return detail.lon() + "," + detail.lat();
                }
            } catch (McpToolException error) {
                if (Thread.currentThread().isInterrupted()) throw error;
                LOG.warn("AMap reference detail could not be verified: {}", error.getClass().getSimpleName());
            }
        }
        if (!tools.containsKey("maps_geo")) return "";
        String placeCity = city.isBlank() ? place.city() : city;
        if (placeCity.isBlank() && place.address().isBlank()) return "";
        FunctionTool geo = tools.get("maps_geo");
        var point = candidates(callRateLimitedPoiTool(geo, arguments(geo, Map.of("city", placeCity, "address",
                        String.join(" ", placeCity, place.address(), place.name()).trim()))), 2)
                .stream().filter(NavigationToolFacade::validCoordinates)
                .filter(candidate -> criteria.matchesArea(place.name(), place.address(),
                        candidate.city(), candidate.district()))
                .filter(candidate -> compact(candidate.address()).contains(compact(place.name()))
                        || !place.address().isBlank()
                        && compact(candidate.address()).contains(compact(place.address())))
                .map(candidate -> candidate.lon() + "," + candidate.lat()).distinct().toList();
        return point.size() == 1 ? point.getFirst() : "";
    }

    private static boolean validCoordinates(Candidate candidate) {
        return Double.isFinite(candidate.lat()) && Double.isFinite(candidate.lon())
                && Math.abs(candidate.lat()) <= 90 && Math.abs(candidate.lon()) <= 180;
    }

    private static Set<CandidateKey> candidateKeys(JsonNode candidates) {
        Set<CandidateKey> keys = new LinkedHashSet<>();
        if (candidates.isArray()) {
            for (JsonNode candidate : candidates) {
                if (keys.size() >= 150) break;
                String name = candidate.path("poiname").asText("");
                if (name.isBlank() || !candidate.path("lat").isNumber() || !candidate.path("lon").isNumber()) continue;
                var parsed = new Candidate(name, candidate.path("lat").asDouble(),
                        candidate.path("lon").asDouble(), "");
                if (validCoordinates(parsed)) keys.add(CandidateKey.from(parsed));
            }
        }
        return keys;
    }

    private static ArrayNode toJson(List<Candidate> candidates, int limit, Set<CandidateKey> excluded,
                                    Constraints constraints) {
        ArrayNode out = JSON.createArrayNode();
        Set<CandidateKey> seen = new LinkedHashSet<>(excluded);
        for (Candidate candidate : candidates) {
            if (out.size() >= limit) break;
            if (candidate.name().isBlank() || !validCoordinates(candidate) || !seen.add(CandidateKey.from(candidate))) {
                continue;
            }
            ObjectNode item = out.addObject();
            item.put("poiname", candidate.name());
            item.put("lat", candidate.lat());
            item.put("lon", candidate.lon());
            if (!candidate.address().isBlank()) item.put("address", candidate.address());
            if (constraints.active()) {
                constraints.addEvidence(item, candidate.name(), candidate.category(), candidate.alias(),
                        candidate.retrievedAt());
            }
        }
        return out;
    }

    private record CandidateKey(String name, long lat, long lon) {
        static CandidateKey from(Candidate candidate) {
            return new CandidateKey(compact(candidate.name()), Math.round(candidate.lat() * 100_000),
                    Math.round(candidate.lon() * 100_000));
        }
    }

    private record Search(String query, String city, String district, String reference, String entrance) {
        static Search parse(String destination, JsonNode args, Constraints conditions) {
            String query = DESTINATION_PREFIX.matcher(destination.strip()).replaceFirst("");
            String city = args.path("city").asText("").strip();
            String district = args.path("district").asText("").strip();
            String reference = args.path("reference").asText("").strip();
            String entrance = args.path("entrance").asText("").strip();
            Matcher cityPrefix = CITY_PREFIX.matcher(query);
            if (cityPrefix.matches()) {
                for (int group = 1; group <= cityPrefix.groupCount(); group += 2) {
                    if (cityPrefix.group(group) == null) continue;
                    if (city.isBlank()) city = cityPrefix.group(group);
                    String remainder = cityPrefix.group(group + 1);
                    String prefix = query.substring(0, query.length() - remainder.length());
                    // 博物馆的城市前缀可属于专名；其他明确类别保留原有城市类别检索。
                    if (prefix.endsWith("的") || compact(remainder).equals(compact(conditions.brand()))
                            || shouldSearchAround(remainder)
                            && !Set.of("博物馆", "科技馆").contains(compact(remainder))) query = remainder;
                    break;
                }
            }
            Matcher relative = REFERENCE_QUERY.matcher(query);
            if (relative.matches()
                    && !Set.of("我", "当前位置", "这里", "这儿", "车辆").contains(relative.group(1))) {
                String spokenReference = relative.group(1);
                if (reference.isBlank()) reference = spokenReference;
                if (matchesReferenceName(reference, spokenReference, city)) query = relative.group(2);
            }
            if (entrance.isBlank()) {
                Matcher component = ENTRANCE.matcher(compact(query));
                if (component.find()) entrance = component.group();
            }
            return new Search(query, city, district, reference, entrance);
        }

        String keywords() {
            String keyword = query;
            if (!entrance.isBlank() && !compact(query).contains(compact(entrance))) keyword += " " + entrance;
            return district.isBlank() ? keyword : district + " " + keyword;
        }

        boolean matchesArea(String name, String address, String resultCity, String resultDistrict) {
            if (!city.isBlank() && !resultCity.isBlank()
                    && !city.replaceFirst("市$", "").equals(resultCity.replaceFirst("市$", ""))) return false;
            if (district.isBlank()) return true;
            if (!resultDistrict.isBlank()) return compact(resultDistrict).equals(compact(district));
            return compact(name + address).contains(compact(district));
        }

        boolean matchesReference(String name, String address) {
            String anchor = compact(reference);
            return matchesReferenceName(reference, name, city) || compact(address).equals(anchor)
                    || !name.isBlank() && (compact(name + address).equals(anchor)
                        || compact(address + name).equals(anchor));
        }

        private static boolean matchesReferenceName(String reference, String name, String city) {
            String anchor = normalizeCityPrefix(reference, city);
            String candidate = normalizeCityPrefix(name, city);
            String cityName = compact(city).replaceFirst("市$", "");
            return candidate.equals(anchor)
                    || !cityName.isBlank() && (candidate.equals(cityName + anchor)
                        || (cityName + candidate).equals(anchor));
        }

        boolean isStreetAddress() {
            return STREET_ADDRESS.matcher(query).matches();
        }

        boolean isNamedAirport() {
            return entrance.isBlank() && NAMED_AIRPORT_QUERY.matcher(compact(query)).matches()
                    && !shouldSearchAround(query);
        }

        boolean matchesNamedAirport(String name, String resultCity, String alias) {
            String cityName = compact(resultCity.isBlank() ? city : resultCity).replaceFirst("市$", "");
            String requested = airportIdentity(query, cityName);
            String actual = airportIdentity(name, cityName);
            return isRootAirport(name) && (actual.equals(requested)
                    || !cityName.isBlank() && actual.equals(cityName + requested)
                    || hasAirportAlias(alias));
        }

        private static String airportIdentity(String name, String verifiedCity) {
            return normalizeCityPrefix(name, verifiedCity).replace("国际", "");
        }

        private static String normalizeCityPrefix(String name, String verifiedCity) {
            String value = compact(name);
            String cityName = compact(verifiedCity).replaceFirst("市$", "");
            // 仅归一已知城市前缀的行政后缀，不删除机场专名内部的“市”。
            if (!cityName.isBlank() && value.startsWith(cityName + "市")) {
                return cityName + value.substring(cityName.length() + 1);
            }
            return value;
        }

        boolean hasAirportAlias(String alias) {
            return java.util.Arrays.stream(alias.split("[;；,，|]"))
                    .map(NavigationToolFacade::compact).anyMatch(compact(query)::equals);
        }

        boolean isChargingStation() {
            return query.contains("充电站");
        }

        boolean acceptsGeocode(Candidate point) {
            if (!city.isBlank() && !point.city().isBlank()
                    && !city.replaceFirst("市$", "").equals(point.city().replaceFirst("市$", ""))) return false;
            if (isNamedAirport() && city.isBlank()) {
                return compact(point.address()).replace("国际", "").contains(compact(query).replace("国际", ""));
            }
            return entrance.isBlank() && !isStreetAddress()
                    || matches(point.name().isBlank() ? point.address() : point.name(),
                        point.address(), point.city(), point.district());
        }

        boolean canEnrich(Place place) {
            if (isNamedAirport()) {
                String placeCity = place.city().isBlank() ? city : place.city();
                // 身份仍须在同一 ID 详情内核验；搜索摘要可能缺少城市/登记别名。
                return isRootAirport(place.name())
                        && (airportIdentity(place.name(), placeCity).endsWith(airportIdentity(query, placeCity))
                            || hasAirportAlias(place.alias()))
                        && matchesArea(place.name(), place.address(), place.city(), place.district());
            }
            return matches(place.name(), place.address(), place.city(), place.district());
        }

        boolean isExactPlace() {
            if (!entrance.isBlank() || isStreetAddress() || isNamedAirport() || shouldSearchAround(query)) return false;
            return CITY_PREFIX.matcher(query).matches()
                    || compact(query).matches(".+(?:站|博物馆)(?:[^馆]*馆)?$");
        }

        boolean matchesExactPlace(String name) {
            String requested = compact(query);
            String actual = compact(name);
            if (actual.equals(requested)) return true;
            // 指定博物馆可列出其馆区，明确馆区则必须完全一致；附属停车/商店不是馆区。
            if (!requested.endsWith("博物馆") || !actual.startsWith(requested)) return false;
            String branch = actual.substring(requested.length());
            return branch.matches("[\\p{IsHan}]{1,16}馆")
                    && !branch.matches(".*(?:停车|商店|餐|咖啡|酒店|地铁|公交|售票|纪念品|文创).*");
        }

        boolean matches(String name, String address, String resultCity, String resultDistrict) {
            return matches(name, address, resultCity, resultDistrict, "");
        }

        boolean matches(String name, String address, String resultCity, String resultDistrict, String alias) {
            if (!matchesArea(name, address, resultCity, resultDistrict)) return false;
            if (isNamedAirport() && !matchesNamedAirport(name, resultCity, alias)) return false;
            if (isExactPlace() && !matchesExactPlace(name)) return false;
            if (isChargingStation() && !name.matches(".*(?:充电站|充电桩|充电中心|超充|充电场).*")) return false;
            String normalizedName = compact(name);
            String normalizedAddress = compact(address);
            if (!entrance.isBlank()) {
                String normalizedQuery = compact(query);
                String requested = entranceKey(compact(entrance));
                Matcher queryComponent = ENTRANCE.matcher(normalizedQuery);
                String base = normalizedQuery;
                String extra = "";
                if (queryComponent.find()) {
                    base = normalizedQuery.substring(0, queryComponent.start());
                    extra = normalizedQuery.substring(queryComponent.end()).replaceFirst("^航站楼", "");
                }

                Matcher component = ENTRANCE.matcher(normalizedName);
                boolean matchesEntrance = false;
                while (component.find()) {
                    if (!entranceKey(component.group()).equals(requested)) continue;
                    String tail = normalizedName.substring(component.end()).replaceFirst("^航站楼", "");
                    if (tail.equals(extra)) {
                        matchesEntrance = true;
                        break;
                    }
                }
                if (!matchesEntrance) return false;
                if (!base.isBlank() && !normalizedName.replace("国际", "").contains(base.replace("国际", ""))) {
                    return false;
                }
            }
            return !isStreetAddress() || normalizedName.contains(compact(query))
                    || normalizedAddress.contains(compact(query));
        }

        private static String entranceKey(String value) {
            Matcher terminal = TERMINAL_NUMBER.matcher(value.replaceFirst("^(t[1-9][0-9]*)航站楼$", "$1"));
            if (!terminal.matches()) return value;
            String number = terminal.group(1) == null ? terminal.group(2) : terminal.group(1);
            if (number.length() == 1) {
                int chinese = "一二三四五六七八九".indexOf(number);
                if (chinese >= 0) number = Integer.toString(chinese + 1);
            }
            return "t" + number;
        }

        ObjectNode toJson() {
            return JSON.createObjectNode().put("query", query).put("city", city)
                    .put("district", district).put("reference", reference).put("entrance", entrance);
        }
    }

    private record Place(String name, String address, String city, String district, String id, String alias) {
        Place(String name, String address) { this(name, address, "", "", "", ""); }
        String key() { return id.isBlank() ? compact(name) : id; }
    }

    private record Candidate(String name, double lat, double lon, String address, String city, String district,
                             String category, String alias, Instant retrievedAt) {
        Candidate(String name, double lat, double lon, String address) {
            this(name, lat, lon, address, "", "", "", "", Instant.now());
        }
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }
}
