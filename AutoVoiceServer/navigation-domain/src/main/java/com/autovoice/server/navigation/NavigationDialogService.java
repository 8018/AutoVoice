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
    /** 已下发但客户端尚未确认展示的候选。 */
    private final NavigationSelectionStore pendingStore;
    /** 客户端已经展示、允许后续语音选择的候选。 */
    private final NavigationSelectionStore activeStore;
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
        this.pendingStore = new InMemoryNavigationSelectionStore(clock, capacity);
        this.activeStore = new InMemoryNavigationSelectionStore(clock, capacity);
        this.policy = new NavigationSelectionPolicy();
    }

    @Override
    public Reply remember(SessionContext context, Reply reply) {
        // 兼容组合:prepare + commit(历史调用方/测试语义不变)
        Reply prepared = prepare(context, reply);
        commit(context, prepared);
        String selectionId = selectionId(prepared);
        if (selectionId != null) adopt(context, selectionId);
        return prepared;
    }

    @Override
    public Reply prepare(SessionContext context, Reply reply) {
        String sessionId = logicalSessionId(context);
        if (sessionId == null || reply == null) return reply;
        Intent intent = reply.intent();
        if (intent == null || !DOMAIN.equals(intent.domain()) || !CHOOSE_INTENT.equals(intent.intent())) {
            return reply;
        }
        SlotValue raw = intent.slots() == null ? null : intent.slots().get(SLOT_CANDIDATES);
        if (raw == null || !(raw.value() instanceof String json)) return reply;

        try {
            List<NavigationCandidate> parsed = parseCandidates(json, true);
            if (parsed.isEmpty()) return Reply.ofText("地点结果无效，请重新搜索");
            String selectionId = UUID.randomUUID().toString();
            String enrichedJson = candidatesJson(parsed);
            Map<String, SlotValue> slots = new LinkedHashMap<>(intent.slots());
            slots.put("selectionId", SlotValue.stringValue(selectionId));
            slots.put(SLOT_CANDIDATES, SlotValue.stringValue(enrichedJson));
            Intent enrichedIntent = Intent.of("1.0", DOMAIN, CHOOSE_INTENT, slots, 1.0,
                    "navigation.dialog", json);
            // D05a:仅丰富,不写共享状态——落库由输出准入层的 commit 完成
            return Reply.ofAction(enrichedIntent, reply.speakText());
        } catch (Exception ignored) {
            return Reply.ofText("地点结果无效，请重新搜索");
        }
    }

    @Override
    public void commit(SessionContext context, Reply reply) {
        String sessionId = logicalSessionId(context);
        if (sessionId == null || reply == null) return;
        Intent intent = reply.intent();
        if (intent == null || !DOMAIN.equals(intent.domain()) || !CHOOSE_INTENT.equals(intent.intent())) {
            return;
        }
        SlotValue sel = intent.slots() == null ? null : intent.slots().get("selectionId");
        SlotValue cands = intent.slots() == null ? null : intent.slots().get(SLOT_CANDIDATES);
        if (sel == null || !(sel.value() instanceof String selectionId)
                || cands == null || !(cands.value() instanceof String json)) {
            return;
        }
        try {
            // 保留 prepare 已嵌入的 candidateId(不重新生成),与客户端所见列表一致
            List<NavigationCandidate> parsed = parseCandidates(json, false);
            if (parsed.isEmpty()) return;
            long now = clock.millis();
            pendingStore.put(sessionId, new PendingNavigationSelection(
                    selectionId, parsed, now, now + ttlMs, false));
        } catch (Exception ignored) {
            // 提交失败不影响已下发的回复;列表缺席表现为"选择已失效"
        }
    }

    @Override
    public void adopt(SessionContext context, String selectionId) {
        String sessionId = logicalSessionId(context);
        if (sessionId == null) return;
        if (selectionId == null || selectionId.isBlank()) {
            pendingStore.find(sessionId).ifPresent(value -> pendingStore.remove(sessionId, value));
            activeStore.find(sessionId).ifPresent(value -> activeStore.remove(sessionId, value));
            return;
        }
        Optional<PendingNavigationSelection> pending = pendingStore.find(sessionId);
        if (pending.isPresent() && pending.get().selectionId().equals(selectionId)) {
            PendingNavigationSelection adopted = pending.get().withAdopted(true);
            activeStore.put(sessionId, adopted);
            pendingStore.remove(sessionId, pending.get());
            return;
        }
        // 已采用列表的重复确认是幂等操作；其他 selectionId 属于迟到确认，直接忽略。
    }

    @Override
    public boolean hasPending(SessionContext context) {
        String sessionId = logicalSessionId(context);
        return sessionId != null && (pendingStore.find(sessionId).isPresent()
                || activeStore.find(sessionId).isPresent());
    }

    @Override
    public Optional<Reply> resolve(SessionContext context, String transcript) {
        if (transcript == null || transcript.isBlank()) return Optional.empty();
        String sessionId = logicalSessionId(context);
        Optional<PendingNavigationSelection> current = activeSelection(context, sessionId);
        if (current.isEmpty()) {
            if (hasSelectionField(context) && policy.isOrdinalAnswer(transcript)) {
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
                removeSelection(sessionId, selection);
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
        if (!removeSelection(sessionId, selection)) {
            return Reply.ofText("地点列表已更新或失效，请重新搜索");
        }
        Intent intent = Intent.of("1.0", DOMAIN, CANCEL_INTENT,
                Map.of("selectionId", SlotValue.stringValue(selection.selectionId())),
                1.0, "navigation.dialog", transcript);
        return Reply.ofAction(intent, "已取消导航");
    }

    private Reply select(SessionContext context, String sessionId,
                         PendingNavigationSelection selection, NavigationCandidate candidate) {
        if (!matchesSelection(context, selection) || !removeSelection(sessionId, selection)) {
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

    private Optional<PendingNavigationSelection> activeSelection(
            SessionContext context, String sessionId) {
        if (sessionId == null) return Optional.empty();
        Object id = context == null ? null : context.attrs().get("navigationSelectionId");
        if (id instanceof String selectionId) {
            if (selectionId.isBlank()) return Optional.empty();
            return activeStore.find(sessionId)
                    .filter(value -> value.selectionId().equals(selectionId));
        }
        // 旧客户端没有采用确认字段：优先使用已采用列表，必要时兼容提交后的候选。
        return activeStore.find(sessionId).or(() -> pendingStore.find(sessionId));
    }

    private static boolean hasSelectionField(SessionContext context) {
        return context != null && context.attrs().containsKey("navigationSelectionId");
    }

    private boolean removeSelection(String sessionId, PendingNavigationSelection selection) {
        return activeStore.remove(sessionId, selection) || pendingStore.remove(sessionId, selection);
    }

    private static String selectionId(Reply reply) {
        if (reply == null || reply.intent() == null || reply.intent().slots() == null) return null;
        SlotValue value = reply.intent().slots().get("selectionId");
        return value != null && value.value() instanceof String id && !id.isBlank() ? id : null;
    }

    private static String logicalSessionId(SessionContext context) {
        if (context == null || context.sessionId() == null || context.sessionId().isBlank()) return null;
        return context.sessionId();
    }

    /** enrich=true:为 prepare 生成 candidateId;false:保留条目内已有 candidateId(commit 复用)。 */
    private static List<NavigationCandidate> parseCandidates(String json, boolean enrich) throws Exception {
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
            String candidateId = enrich
                    ? UUID.randomUUID().toString()
                    : item.path("candidateId").asText("");
            if (candidateId.isBlank()) return List.of();
            ObjectNode stored = item.deepCopy();
            stored.put("candidateId", candidateId);
            result.add(new NavigationCandidate(candidateId, name,
                    item.path("address").asText(""), lat.asDouble(), lon.asDouble(),
                    stored.toString()));
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
