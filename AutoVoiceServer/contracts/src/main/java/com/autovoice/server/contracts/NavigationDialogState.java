package com.autovoice.server.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Session-scoped, deterministic second-turn resolver for navigation candidate selection. */
public final class NavigationDialogState {
    public static final String DOMAIN = "navigation";
    public static final String CHOOSE_INTENT = "choose_destination";
    public static final String CANCEL_INTENT = "cancel_navigation";
    public static final String NAVIGATE_INTENT = "navigate";
    public static final String SLOT_QUERY = "query";
    public static final String SLOT_CANDIDATES = "candidates";
    public static final long DEFAULT_TTL_MS = 120_000;
    private static final int MAX_PENDING_SESSIONS = 1_000;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern ARABIC_ORDINAL = Pattern.compile("(\\d+)");
    private static final Pattern NEW_NAVIGATION_REQUEST = Pattern.compile(
            ".*(导航去|导航到|带我去|我要去|想去|前往|开车去|出发去).*");
    private static final Map<Character, Integer> CHINESE_NUMBERS = Map.of(
            '一', 1, '二', 2, '两', 2, '三', 3, '四', 4, '五', 5,
            '六', 6, '七', 7, '八', 8, '九', 9);

    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();
    private final Clock clock;
    private final long ttlMs;

    public NavigationDialogState() {
        this(Clock.systemUTC(), DEFAULT_TTL_MS);
    }

    NavigationDialogState(Clock clock, long ttlMs) {
        this.clock = clock;
        this.ttlMs = ttlMs;
    }

    /** Remember a choose_destination action produced by either online backend. */
    public Reply remember(SessionContext context, Reply reply) {
        if (context == null || context.sessionId() == null || reply == null) return reply;
        Intent intent = reply.intent();
        if (intent == null || !DOMAIN.equals(intent.domain()) || !CHOOSE_INTENT.equals(intent.intent())) return reply;
        SlotValue raw = intent.slots() == null ? null : intent.slots().get(SLOT_CANDIDATES);
        if (raw == null || !(raw.value() instanceof String json)) return reply;
        List<Candidate> candidates = parseCandidates(json);
        if (candidates.isEmpty()) return Reply.ofText("地点结果无效，请重新搜索");
        String selectionId = java.util.UUID.randomUUID().toString();
        try {
            var array = JSON.createArrayNode();
            for (Candidate candidate : candidates) {
                var item = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(candidate.raw);
                item.put("candidateId", java.util.UUID.randomUUID().toString());
                array.add(item);
            }
            candidates = parseCandidates(array.toString());
            Map<String, SlotValue> slots = new LinkedHashMap<>(intent.slots());
            slots.put("selectionId", SlotValue.stringValue(selectionId));
            slots.put(SLOT_CANDIDATES, SlotValue.stringValue(array.toString()));
            Intent enriched = Intent.of("1.0", DOMAIN, CHOOSE_INTENT, slots, 1.0, "navigation.dialog", json);
            reply = Reply.ofAction(enriched, reply.speakText());
        } catch (Exception error) {
            return Reply.ofText("地点结果无效，请重新搜索");
        }
        pruneIfNeeded();
        pending.put(context.sessionId(), new Pending(candidates, clock.millis() + ttlMs, selectionId));
        return reply;
    }

    public boolean hasPending(SessionContext context) {
        return current(context).isPresent();
    }

    /**
     * Resolve only explicit dialog answers. Unrelated speech returns empty so normal car-control or
     * chat handling remains available while the candidate popup stays visible until TTL/cancel.
     */
    public Optional<Reply> resolve(SessionContext context, String transcript) {
        Optional<Pending> snapshot = current(context);
        if (transcript == null || transcript.isBlank()) return Optional.empty();
        if (snapshot.isEmpty()) {
            if (context != null && context.attrs().get("navigationSelectionId") instanceof String id
                    && !id.isBlank() && ordinal(compact(transcript)) != null) {
                return Optional.of(Reply.ofText("地点选择已失效，请重新搜索"));
            }
            return Optional.empty();
        }
        String compact = compact(transcript);
        if (compact.matches(".*(取消|算了|不去了|关闭).*")) {
            if (!matchesSelection(context, snapshot.get())) return Optional.of(Reply.ofText("地点列表已更新，请重新搜索"));
            pending.remove(context.sessionId(), snapshot.get());
            Intent cancel = Intent.of("1.0", DOMAIN, CANCEL_INTENT, Map.of("selectionId", SlotValue.stringValue(snapshot.get().selectionId)), 1.0,
                    "navigation.dialog", transcript);
            return Optional.of(Reply.ofAction(cancel, "已取消导航"));
        }

        Integer ordinal = ordinal(compact);
        if (ordinal != null) {
            if (ordinal < 1 || ordinal > snapshot.get().candidates.size()) {
                return Optional.of(Reply.ofText("没有第" + ordinal + "个，请重新选择"));
            }
            return Optional.of(select(context, snapshot.get(), snapshot.get().candidates.get(ordinal - 1)));
        }

        String choice = compact.replace("选择", "").replace("选", "")
                .replace("导航到", "").replace("导航去", "").replace("去", "")
                .replace("这个", "").replace("那个", "");
        if (choice.length() >= 2) {
            List<Candidate> exactNameMatches = snapshot.get().candidates.stream()
                    .filter(candidate -> compact(candidate.poiname).equals(choice)).toList();
            if (exactNameMatches.size() == 1) {
                return Optional.of(select(context, snapshot.get(), exactNameMatches.getFirst()));
            }
            List<Candidate> exactAddressMatches = snapshot.get().candidates.stream()
                    .filter(candidate -> !candidate.address.isBlank()
                            && compact(candidate.address).equals(choice)).toList();
            if (exactAddressMatches.size() == 1) {
                return Optional.of(select(context, snapshot.get(), exactAddressMatches.getFirst()));
            }
            List<Candidate> matches = snapshot.get().candidates.stream()
                    .filter(candidate -> matches(candidate, choice)).toList();
            if (matches.size() == 1) {
                return Optional.of(select(context, snapshot.get(), matches.getFirst()));
            }
            // “导航去机场”是新的查询，不是对当前候选的模糊选择。清掉旧候选并交回
            // 正常 LLM/Skill 链路重新搜索；否则旧实现会只播“有多个相似地点”，既不刷新
            // 弹窗，也会让旧候选继续污染后续轮次。
            if (NEW_NAVIGATION_REQUEST.matcher(compact).matches()) {
                pending.remove(context.sessionId(), snapshot.get());
                return Optional.empty();
            }
            if (matches.size() > 1) {
                return Optional.of(Reply.ofText("有多个相似地点，请说第几个或更完整的地址"));
            }
        }
        return Optional.empty();
    }

    private Optional<Pending> current(SessionContext context) {
        if (context == null || context.sessionId() == null) return Optional.empty();
        Pending value = pending.get(context.sessionId());
        if (value == null) return Optional.empty();
        if (clock.millis() > value.expiresAtMs) {
            pending.remove(context.sessionId(), value);
            return Optional.empty();
        }
        return Optional.of(value);
    }

    private void pruneIfNeeded() {
        if (pending.size() < MAX_PENDING_SESSIONS) return;
        long now = clock.millis();
        pending.entrySet().removeIf(entry -> now > entry.getValue().expiresAtMs);
        if (pending.size() >= MAX_PENDING_SESSIONS) {
            pending.keySet().stream().findFirst().ifPresent(pending::remove);
        }
    }

    private boolean matchesSelection(SessionContext context, Pending snapshot) {
        // Missing field is the legacy-client path; modern clients explicitly send empty when no list is visible.
        Object id = context.attrs().get("navigationSelectionId");
        return id == null || snapshot.selectionId.equals(id);
    }

    private Reply select(SessionContext context, Pending snapshot, Candidate candidate) {
        if (!matchesSelection(context, snapshot) || !pending.remove(context.sessionId(), snapshot)) {
            return Reply.ofText("地点列表已更新或失效，请重新搜索");
        }
        Map<String, SlotValue> slots = new LinkedHashMap<>();
        slots.put("selectionId", SlotValue.stringValue(snapshot.selectionId));
        slots.put("candidateId", SlotValue.stringValue(candidate.candidateId));
        slots.put("poiname", SlotValue.stringValue(candidate.poiname));
        slots.put("lat", SlotValue.number(candidate.lat));
        slots.put("lon", SlotValue.number(candidate.lon));
        Intent intent = Intent.of("1.0", DOMAIN, NAVIGATE_INTENT, slots, 1.0,
                "navigation.dialog", candidate.raw);
        return Reply.ofAction(intent, "好的，开始导航去" + candidate.poiname);
    }

    private static boolean matches(Candidate candidate, String choice) {
        String name = compact(candidate.poiname);
        String address = compact(candidate.address);
        return name.contains(choice) || choice.contains(name)
                || (!address.isBlank() && (address.contains(choice) || choice.contains(address)));
    }

    private static Integer ordinal(String text) {
        Matcher arabic = ARABIC_ORDINAL.matcher(text);
        if (arabic.find() && (text.matches("\\d+[个家]?") || text.contains("第") || text.startsWith("选"))) {
            return Integer.parseInt(arabic.group(1));
        }
        for (int i = 0; i < text.length(); i++) {
            Integer value = CHINESE_NUMBERS.get(text.charAt(i));
            if (value != null && (text.contains("第") || text.contains("选")
                    || text.endsWith("个") || text.endsWith("家"))) return value;
        }
        return null;
    }

    private static String compact(String text) {
        String normalized = text.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s，。！？、,.!?;；:：()（）]", "");
        StringBuilder out = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            Integer digit = CHINESE_NUMBERS.get(normalized.charAt(i));
            if (digit == null) out.append(normalized.charAt(i));
            else out.append(digit);
        }
        return out.toString();
    }

    private static List<Candidate> parseCandidates(String json) {
        List<Candidate> out = new ArrayList<>();
        try {
            JsonNode array = JSON.readTree(json);
            if (!array.isArray()) return List.of();
            for (JsonNode item : array) {
                String name = item.path("poiname").asText("");
                JsonNode lat = item.path("lat");
                JsonNode lon = item.path("lon");
                if (name.isBlank() || !lat.isNumber() || !lon.isNumber()
                        || !Double.isFinite(lat.asDouble()) || !Double.isFinite(lon.asDouble())
                        || Math.abs(lat.asDouble()) > 90 || Math.abs(lon.asDouble()) > 180) return List.of();
                out.add(new Candidate(name, item.path("address").asText(""),
                        lat.asDouble(), lon.asDouble(), item.toString(), item.path("candidateId").asText("")));
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return List.copyOf(out);
    }

    private record Pending(List<Candidate> candidates, long expiresAtMs, String selectionId) {}
    private record Candidate(String poiname, String address, double lat, double lon, String raw, String candidateId) {}
}
