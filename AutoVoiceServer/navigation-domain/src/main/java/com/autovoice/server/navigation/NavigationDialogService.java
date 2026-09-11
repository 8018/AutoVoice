package com.autovoice.server.navigation;

import com.autovoice.server.contracts.Intent;
import com.autovoice.server.contracts.NavigationDialog;
import com.autovoice.server.contracts.Reply;
import com.autovoice.server.contracts.SessionContext;
import com.autovoice.server.contracts.SlotValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Navigation candidate dialog application service.
 *
 * <p>The contract exposes only the dialog port. Matching is stateless and pending choices are
 * stored by logical session id so a transport reconnect does not change dialog ownership.</p>
 */
public final class NavigationDialogService implements NavigationDialog {
    public static final String DOMAIN = "navigation";
    public static final String CHOOSE_INTENT = "choose_destination";
    public static final String CANCEL_INTENT = "cancel_navigation";
    public static final String NAVIGATE_INTENT = "navigate";
    public static final String SLOT_CANDIDATES = "candidates";
    public static final long DEFAULT_TTL_MS = 120_000;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Clock clock;
    private final long ttlMs;
    private final NavigationSelectionStore store;
    private final NavigationSelectionPolicy policy;

    public NavigationDialogService() {
        this(Clock.systemUTC(), DEFAULT_TTL_MS, InMemoryNavigationSelectionStore.DEFAULT_CAPACITY);
    }

    public NavigationDialogService(Clock clock, long ttlMs) {
        this(clock, ttlMs, InMemoryNavigationSelectionStore.DEFAULT_CAPACITY);
    }

    public NavigationDialogService(Clock clock, long ttlMs, int capacity) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        if (ttlMs <= 0) throw new IllegalArgumentException("ttlMs must be positive");
        this.ttlMs = ttlMs;
        this.store = new InMemoryNavigationSelectionStore(clock, capacity);
        this.policy = new NavigationSelectionPolicy();
    }

    @Override
    public Reply remember(SessionContext context, Reply reply) {
        String sessionId = logicalSessionId(context);
        if (sessionId == null || reply == null) return reply;
        Intent intent = reply.intent();
        if (intent == null || !DOMAIN.equals(intent.domain()) || !CHOOSE_INTENT.equals(intent.intent())) {
            return reply;
        }
        SlotValue raw = intent.slots() == null ? null : intent.slots().get(SLOT_CANDIDATES);
        if (raw == null || !(raw.value() instanceof String json)) return reply;

        try {
            List<NavigationCandidate> parsed = parseAndEnrich(json);
            if (parsed.isEmpty()) return Reply.ofText("地点结果无效，请重新搜索");
            String selectionId = UUID.randomUUID().toString();
            String enrichedJson = candidatesJson(parsed);
            Map<String, SlotValue> slots = new LinkedHashMap<>(intent.slots());
            slots.put("selectionId", SlotValue.stringValue(selectionId));
            slots.put(SLOT_CANDIDATES, SlotValue.stringValue(enrichedJson));
            Intent enrichedIntent = Intent.of("1.0", DOMAIN, CHOOSE_INTENT, slots, 1.0,
                    "navigation.dialog", json);
            long now = clock.millis();
            store.put(sessionId, new PendingNavigationSelection(
                    selectionId, parsed, now, now + ttlMs));
            return Reply.ofAction(enrichedIntent, reply.speakText());
        } catch (Exception ignored) {
            return Reply.ofText("地点结果无效，请重新搜索");
        }
    }

    @Override
    public boolean hasPending(SessionContext context) {
        String sessionId = logicalSessionId(context);
        return sessionId != null && store.find(sessionId).isPresent();
    }

    @Override
    public Optional<Reply> resolve(SessionContext context, String transcript) {
        if (transcript == null || transcript.isBlank()) return Optional.empty();
        String sessionId = logicalSessionId(context);
        Optional<PendingNavigationSelection> current = sessionId == null
                ? Optional.empty() : store.find(sessionId);
        if (current.isEmpty()) {
            if (hasModernSelectionId(context) && policy.isOrdinalAnswer(transcript)) {
                return Optional.of(Reply.ofText("地点选择已失效，请重新搜索"));
            }
            return Optional.empty();
        }

        PendingNavigationSelection selection = current.get();
        NavigationSelectionPolicy.Decision decision = policy.decide(selection, transcript);
        return switch (decision.type()) {
            case NO_MATCH -> Optional.empty();
            case OUT_OF_RANGE -> Optional.of(
                    Reply.ofText("没有第" + decision.ordinal() + "个，请重新选择"));
            case AMBIGUOUS -> Optional.of(
                    Reply.ofText("有多个相似地点，请说第几个或更完整的地址"));
            case FRESH_SEARCH -> {
                store.remove(sessionId, selection);
                yield Optional.empty();
            }
            case CANCEL -> Optional.of(cancel(context, sessionId, selection, transcript));
            case SELECT -> Optional.of(select(context, sessionId, selection, decision.candidate()));
        };
    }

    private Reply cancel(SessionContext context, String sessionId,
                         PendingNavigationSelection selection, String transcript) {
        if (!matchesSelection(context, selection)) {
            return Reply.ofText("地点列表已更新，请重新搜索");
        }
        if (!store.remove(sessionId, selection)) {
            return Reply.ofText("地点列表已更新或失效，请重新搜索");
        }
        Intent intent = Intent.of("1.0", DOMAIN, CANCEL_INTENT,
                Map.of("selectionId", SlotValue.stringValue(selection.selectionId())),
                1.0, "navigation.dialog", transcript);
        return Reply.ofAction(intent, "已取消导航");
    }

    private Reply select(SessionContext context, String sessionId,
                         PendingNavigationSelection selection, NavigationCandidate candidate) {
        if (!matchesSelection(context, selection) || !store.remove(sessionId, selection)) {
            return Reply.ofText("地点列表已更新或失效，请重新搜索");
        }
        Map<String, SlotValue> slots = new LinkedHashMap<>();
        slots.put("selectionId", SlotValue.stringValue(selection.selectionId()));
        slots.put("candidateId", SlotValue.stringValue(candidate.candidateId()));
        slots.put("poiname", SlotValue.stringValue(candidate.poiname()));
        slots.put("lat", SlotValue.number(candidate.lat()));
        slots.put("lon", SlotValue.number(candidate.lon()));
        Intent intent = Intent.of("1.0", DOMAIN, NAVIGATE_INTENT, slots, 1.0,
                "navigation.dialog", candidate.rawJson());
        return Reply.ofAction(intent, "好的，开始导航去" + candidate.poiname());
    }

    private static boolean matchesSelection(SessionContext context,
                                            PendingNavigationSelection selection) {
        Object id = context == null ? null : context.attrs().get("navigationSelectionId");
        // Missing means a legacy client. Modern clients explicitly send empty when no list is shown.
        return id == null || selection.selectionId().equals(id);
    }

    private static boolean hasModernSelectionId(SessionContext context) {
        Object id = context == null ? null : context.attrs().get("navigationSelectionId");
        return id instanceof String value && !value.isBlank();
    }

    private static String logicalSessionId(SessionContext context) {
        if (context == null || context.sessionId() == null || context.sessionId().isBlank()) return null;
        return context.sessionId();
    }

    private static List<NavigationCandidate> parseAndEnrich(String json) throws Exception {
        JsonNode array = JSON.readTree(json);
        if (!array.isArray()) return List.of();
        List<NavigationCandidate> result = new ArrayList<>();
        for (JsonNode item : array) {
            String name = item.path("poiname").asText("");
            JsonNode lat = item.path("lat");
            JsonNode lon = item.path("lon");
            if (name.isBlank() || !lat.isNumber() || !lon.isNumber()
                    || !Double.isFinite(lat.asDouble()) || !Double.isFinite(lon.asDouble())
                    || Math.abs(lat.asDouble()) > 90 || Math.abs(lon.asDouble()) > 180) {
                return List.of();
            }
            ObjectNode enriched = item.deepCopy();
            String candidateId = UUID.randomUUID().toString();
            enriched.put("candidateId", candidateId);
            result.add(new NavigationCandidate(candidateId, name,
                    item.path("address").asText(""), lat.asDouble(), lon.asDouble(),
                    enriched.toString()));
        }
        return List.copyOf(result);
    }

    private static String candidatesJson(List<NavigationCandidate> candidates) throws Exception {
        var array = JSON.createArrayNode();
        for (NavigationCandidate candidate : candidates) {
            array.add(JSON.readTree(candidate.rawJson()));
        }
        return array.toString();
    }
}
