package com.autovoice.server.navigation;

import com.autovoice.server.contracts.Intent;
import com.autovoice.server.contracts.NavigationRoutePreview.Preference;
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
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

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
    private static final String PREVIEW_PREFERENCE = "routePreviewPreference";
    public static final String SLOT_CANDIDATES = "candidates";
    public static final long DEFAULT_TTL_MS = 120_000;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern LIVE_ROUTE_QUERY = Pattern.compile(
            "^(?:请问)?(?:还有|还要|还需|剩下|剩余).{0,8}(?:多久|多远|多少(?:时间|分钟|公里|米))"
            + "(?:(?:能|才)?到.{0,36})?[？?。！!]*$"
            + "|(?:几点|什么时候|何时)(?:到达|能到|到目的地|到终点)");
    private static final Pattern PREVIEW_QUERY = Pattern.compile(
            "(?:去|前往|开到|到).{1,48}(?:多久|多远|多少(?:时间|分钟|公里|米))"
            + "|(?:预览|比较|对比|看看|看下|算一下|算下|查一下|查下|规划).{0,40}(?:路线|路程|耗时)"
            + "|(?:路线|路程).{0,8}(?:预览|比较|对比|怎么走)"
            + "|(?:只|先)(?:算|查|看).{0,45}(?:不|别|不用|先别|不要)(?:开始)?导航");

    // Keep in step with the client's exact SavedPlaceCommand.parse expressions.
    private static final Pattern SAVED_PLACE_COMMAND = Pattern.compile(
            "(?:带我|送我)?回家|(?:导航|开车)(?:回家|到家|去我家)|去我家"
            + "|(?:带我|送我|导航|开车)?去(?:我?的)?公司|(?:导航)?到公司|去上班"
            + "|把(?:这里|当前位置)(?:设|设置|保存)(?:为|成)(?:我的)?(?:家|家庭地址)"
            + "|家(?:搬到|换到)(?:这里|当前位置)了?"
            + "|把(?:这里|当前位置)(?:设|设置|保存)(?:为|成)(?:我的)?(?:公司|公司地址)"
            + "|公司(?:搬到|换到)(?:这里|当前位置)了?"
            + "|(?:删除|清除)(?:我的)?(?:家庭地址|公司地址)");

    private final Clock clock;
    private final long ttlMs;
    /** 已下发但客户端尚未确认展示的候选。 */
    private final NavigationSelectionStore pendingStore;
    /** 客户端已经展示、允许后续语音选择的候选。 */
    private final NavigationSelectionStore activeStore;
    private final NavigationSelectionPolicy policy;
    private final Function<String, CompletableFuture<Reply>> search;

    public NavigationDialogService() {
        this(Clock.systemUTC(), DEFAULT_TTL_MS, InMemoryNavigationSelectionStore.DEFAULT_CAPACITY);
    }

    public NavigationDialogService(Function<String, CompletableFuture<Reply>> search) {
        this(Clock.systemUTC(), DEFAULT_TTL_MS, InMemoryNavigationSelectionStore.DEFAULT_CAPACITY, search);
    }

    public NavigationDialogService(Clock clock, long ttlMs) {
        this(clock, ttlMs, InMemoryNavigationSelectionStore.DEFAULT_CAPACITY);
    }

    public NavigationDialogService(Clock clock, long ttlMs, int capacity) {
        this(clock, ttlMs, capacity, null);
    }

    private NavigationDialogService(Clock clock, long ttlMs, int capacity,
                                    Function<String, CompletableFuture<Reply>> search) {
        this.search = search;
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
            String searchState = searchState(intent);
            Map<String, SlotValue> slots = new LinkedHashMap<>(intent.slots());
            slots.remove("navigationOperation");
            slots.remove("taskId");
            slots.remove("taskRevision");
            slots.remove("interactionId");
            slots.put("selectionId", SlotValue.stringValue(selectionId));
            if (isTaskDialog(context)) slots.put("taskDialogVersion", SlotValue.number(1));
            slots.put(SLOT_CANDIDATES, SlotValue.stringValue(enrichedJson));
            Intent enrichedIntent = Intent.of("1.0", DOMAIN, CHOOSE_INTENT, slots, 1.0,
                    "navigation.dialog", searchState);
            // D05a:仅丰富,不写共享状态——落库由输出准入层的 commit 完成
            return Reply.ofAction(enrichedIntent, reply.speakText());
        } catch (Exception ignored) {
            return Reply.ofText("地点结果无效，请重新搜索");
        }
    }

    @Override
    public Reply prepareModel(SessionContext context, Reply reply) {
        Intent intent = reply == null ? null : reply.intent();
        if (isTaskDialog(context) && intent != null && DOMAIN.equals(intent.domain())
                && NAVIGATE_INTENT.equals(intent.intent())) {
            Map<String, SlotValue> slots = new LinkedHashMap<>(intent.slots());
            // Classification comes from this entry point, never a model-controlled source string.
            slots.remove("selectionId");
            slots.remove("candidateId");
            bindTask(context, slots, "start_new");
            return Reply.ofAction(Intent.of("1.0", DOMAIN, NAVIGATE_INTENT, slots,
                    intent.confidence(), intent.source(), intent.rawSemantic()), reply.speakText());
        }
        return prepare(context, reply);
    }

    @Override
    public synchronized void commit(SessionContext context, Reply reply) {
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
            SlotValue mode = intent.slots().get(PREVIEW_PREFERENCE);
            Preference previewPreference = mode == null ? null : Preference.valueOf(String.valueOf(mode.value()));
            pendingStore.put(sessionId, new PendingNavigationSelection(
                    selectionId, parsed, now, now + ttlMs, false, intent.rawSemantic(), previewPreference));
        } catch (Exception ignored) {
            // 提交失败不影响已下发的回复;列表缺席表现为"选择已失效"
        }
    }

    @Override
    public synchronized void adopt(SessionContext context, String selectionId) {
        String sessionId = logicalSessionId(context);
        if (sessionId == null) return;
        if (selectionId == null || selectionId.isBlank()) {
            pendingStore.find(sessionId).ifPresent(value -> pendingStore.remove(sessionId, value));
            activeStore.find(sessionId).ifPresent(value -> activeStore.remove(sessionId, value));
            return;
        }
        adoptExact(context, selectionId);
    }

    @Override
    public synchronized boolean adoptExact(SessionContext context, String selectionId) {
        String sessionId = logicalSessionId(context);
        if (sessionId == null || selectionId == null || selectionId.isBlank()) return false;
        if (hasAdopted(context, selectionId)) return true;
        Optional<PendingNavigationSelection> pending = pendingStore.find(sessionId);
        if (pending.isPresent() && pending.get().selectionId().equals(selectionId)) {
            PendingNavigationSelection adopted = pending.get().withAdopted(true);
            activeStore.put(sessionId, adopted);
            pendingStore.remove(sessionId, pending.get());
            return true;
        }
        return false;
    }

    @Override
    public synchronized boolean hasAdopted(SessionContext context, String selectionId) {
        String sessionId = logicalSessionId(context);
        return sessionId != null && selectionId != null && activeStore.find(sessionId)
                .filter(value -> value.selectionId().equals(selectionId)).isPresent();
    }

    @Override
    public synchronized void closeExact(SessionContext context, String selectionId) {
        String sessionId = logicalSessionId(context);
        if (sessionId == null || selectionId == null || selectionId.isBlank()) return;
        pendingStore.find(sessionId).filter(value -> value.selectionId().equals(selectionId))
                .ifPresent(value -> pendingStore.remove(sessionId, value));
        activeStore.find(sessionId).filter(value -> value.selectionId().equals(selectionId))
                .ifPresent(value -> activeStore.remove(sessionId, value));
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
            case REJECTED -> Optional.of(Reply.ofText("请说其他候选的名称或序号，也可以换一批"));
            case NEXT_BATCH -> Optional.of(Reply.ofText("没有更多候选，请说更具体的地点名称"));
            case REFINE -> Optional.of(Reply.ofText("请提供更具体的地点名称"));
            case AMBIGUOUS -> Optional.of(
                    Reply.ofText("有多个相似地点，请说第几个或更完整的地址"));
            case FRESH_SEARCH -> {
                // Modern task-dialog clients close the exact old context only after the new reply
                // is adopted. Legacy clients retain their historical consume-on-proposal policy.
                if (!isTaskDialog(context)) removeSelection(sessionId, selection);
                yield Optional.empty();
            }
            case CANCEL -> Optional.of(cancel(context, sessionId, selection, transcript));
            case SELECT -> Optional.of(select(context, sessionId, selection, decision.candidate()));
        };
    }

    @Override
    public CompletableFuture<Reply> complete(SessionContext context, String transcript,
                                              Supplier<CompletableFuture<Reply>> modelCall) {
        if (isSavedPlaceCommand(transcript)) {
            return CompletableFuture.completedFuture(Reply.ofText("请在支持常用地址的设备上设置和使用家或公司地址。"));
        }
        String normalized = transcript == null ? "" : transcript.replaceAll("(?U)\\s", "");
        if (LIVE_ROUTE_QUERY.matcher(normalized).find()) {
            return CompletableFuture.completedFuture(Reply.ofText("当前行程的实时剩余时间和距离请在地图导航中查看。"));
        }
        Preference requestedPreference = routeQueryPreference(normalized);
        if (requestedPreference != null) {
            return preservePreview(NavigationDialog.super.complete(context, transcript, modelCall),
                    requestedPreference);
        }
        String sessionId = logicalSessionId(context);
        var current = activeSelection(context, sessionId);
        if (search != null && current.isPresent() && transcript != null && !transcript.isBlank()) {
            PendingNavigationSelection selection = current.get();
            var decision = policy.decide(selection, transcript);
            if (decision.type() == NavigationSelectionPolicy.Type.NEXT_BATCH
                    || decision.type() == NavigationSelectionPolicy.Type.REFINE) {
                return searchAgain(context, sessionId, selection, decision);
            }
        }
        return NavigationDialog.super.complete(context, transcript, modelCall);
    }

    private static Preference routeQueryPreference(String text) {
        if (text.contains("不走高速") || text.contains("避开高速")) return Preference.AVOID_HIGHWAYS;
        if (text.contains("高速优先") || text.contains("优先走高速")) return Preference.PREFER_HIGHWAYS;
        if (text.contains("少收费") || text.contains("避免收费")) return Preference.LESS_TOLLS;
        if (text.contains("避开拥堵") || text.contains("躲避拥堵")) return Preference.AVOID_CONGESTION;
        if (text.contains("最快路线") || text.contains("路线最快")) return Preference.FASTEST;
        return PREVIEW_QUERY.matcher(text).find() ? Preference.DEFAULT : null;
    }

    private static CompletableFuture<Reply> preservePreview(
            CompletableFuture<Reply> source, Preference preference) {
        CompletableFuture<Reply> result = new CompletableFuture<>() {
            @Override public boolean cancel(boolean mayInterruptIfRunning) {
                boolean cancelled = super.cancel(mayInterruptIfRunning);
                if (cancelled) source.cancel(mayInterruptIfRunning);
                return cancelled;
            }
        };
        source.whenComplete((reply, error) -> {
            if (error != null) result.completeExceptionally(error);
            else result.complete(previewReply(reply, preference, false));
        });
        return result;
    }

    private static Reply previewReply(Reply reply, Preference preference, boolean trustedSearch) {
        Intent intent = reply == null ? null : reply.intent();
        if (intent == null || !DOMAIN.equals(intent.domain())
                || intent.slots() == null
                || !List.of(CHOOSE_INTENT, "draft_trip", "preview_routes").contains(intent.intent())) {
            if (trustedSearch && reply != null && intent == null) return reply;
            return Reply.ofText("请说要查询的具体地点，我可以帮你预览路线。");
        }
        Map<String, SlotValue> slots = new LinkedHashMap<>(intent.slots());
        slots.put(PREVIEW_PREFERENCE, SlotValue.stringValue(preference.name()));
        return Reply.ofAction(Intent.of(intent.schemaVersion(), intent.domain(), intent.intent(),
                slots, intent.confidence(), intent.source(), intent.rawSemantic()), reply.speakText());
    }

    private static boolean isSavedPlaceCommand(String transcript) {
        if (transcript == null) return false;
        String normalized = transcript.replaceAll("(?U)\\s", "")
                .replaceAll("^[。！？!?.，,]+|[。！？!?.，,]+$", "").replaceFirst("^请", "");
        return SAVED_PLACE_COMMAND.matcher(normalized).matches();
    }

    private CompletableFuture<Reply> searchAgain(SessionContext context, String sessionId,
                                                   PendingNavigationSelection selection,
                                                   NavigationSelectionPolicy.Decision decision) {
        ObjectNode request;
        try {
            if (selection.searchState() == null
                    || !(JSON.readTree(selection.searchState()) instanceof ObjectNode stored)) {
                return CompletableFuture.completedFuture(Reply.ofText("请说地点名称，重新搜索"));
            }
            request = stored;
            String query = request.remove("query").asText();
            request.putArray("destinations").add(query);
            if (selection.previewPreference() != null) {
                request.put(PREVIEW_PREFERENCE, selection.previewPreference().name());
            }
            if (decision.type() == NavigationSelectionPolicy.Type.NEXT_BATCH) {
                var excluded = request.withArray("excluded");
                for (NavigationCandidate candidate : selection.candidates()) {
                    excluded.addObject().put("poiname", candidate.poiname())
                            .put("lat", candidate.lat()).put("lon", candidate.lon());
                }
            } else {
                String area = decision.qualifier();
                if (!area.matches("[\\p{IsHan}]{2,16}")) {
                    return CompletableFuture.completedFuture(Reply.ofText("请说要查找的城市或区县"));
                }
                if (area.endsWith("区") || area.endsWith("县")) {
                    request.put("district", area);
                } else {
                    request.put("city", area);
                    request.remove("district");
                }
                request.remove("excluded");
            }
            bindLocation(context, request);
        } catch (Exception invalidState) {
            return CompletableFuture.completedFuture(Reply.ofText("地点列表已失效，请重新搜索"));
        }

        CompletableFuture<Reply> source;
        try {
            source = search.apply(request.toString());
        } catch (RuntimeException error) {
            return CompletableFuture.completedFuture(Reply.ofText("地点搜索暂时失败，请稍后重试"));
        }
        CompletableFuture<Reply> result = new CompletableFuture<>() {
            @Override public boolean cancel(boolean mayInterruptIfRunning) {
                boolean cancelled = super.cancel(mayInterruptIfRunning);
                if (cancelled) source.cancel(mayInterruptIfRunning);
                return cancelled;
            }
        };
        source.whenComplete((reply, error) -> {
            if (result.isDone()) return;
            if (activeSelection(context, sessionId).filter(selection::equals).isEmpty()) {
                result.complete(Reply.ofText("地点列表已失效，请重新搜索"));
            } else if (error != null || reply == null) {
                result.complete(Reply.ofText("地点搜索暂时失败，请稍后重试"));
            } else {
                Reply replacement = withExclusions(reply, request);
                if (selection.previewPreference() != null) {
                    replacement = previewReply(replacement, selection.previewPreference(), true);
                }
                result.complete(prepare(context, replacement));
            }
        });
        return result;
    }

    private static Reply withExclusions(Reply reply, ObjectNode request) {
        Intent intent = reply.intent();
        if (intent == null || !DOMAIN.equals(intent.domain()) || !CHOOSE_INTENT.equals(intent.intent())
                || !request.has("excluded")) return reply;
        try {
            if (JSON.readTree(intent.rawSemantic()) instanceof ObjectNode resolution) {
                resolution.set("excluded", request.get("excluded"));
                return Reply.ofAction(Intent.of(intent.schemaVersion(), intent.domain(), intent.intent(),
                        intent.slots(), intent.confidence(), intent.source(), resolution.toString()), reply.speakText());
            }
        } catch (Exception ignored) {
            // The prepared candidate list remains selectable without further search metadata.
        }
        return reply;
    }

    private static String searchState(Intent intent) {
        if (!"navigation.resolve".equals(intent.source()) || intent.rawSemantic() == null) return null;
        try {
            JsonNode raw = JSON.readTree(intent.rawSemantic());
            if (!(raw.path("search") instanceof ObjectNode fields)) return null;
            ObjectNode state = JSON.createObjectNode();
            for (String key : List.of("query", "city", "district", "reference", "entrance", "routePreviewPreference")) {
                String value = fields.path(key).asText("");
                if (!value.isBlank() && value.length() <= 256) state.put(key, value);
            }
            if (!state.has("query")) return null;
            JsonNode conditions = fields.path("constraints");
            if (conditions.isArray() && conditions.size() == 1 && conditions.get(0).isObject()) {
                state.set("constraints", conditions);
            }
            if (raw.path("excluded").isArray()) state.set("excluded", raw.path("excluded"));
            return state.toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void bindLocation(SessionContext context, ObjectNode request) {
        Object lat = context.attrs().get("latitude");
        Object lon = context.attrs().get("longitude");
        if (lat instanceof Number a && lon instanceof Number b
                && Double.isFinite(a.doubleValue()) && Double.isFinite(b.doubleValue())
                && Math.abs(a.doubleValue()) <= 90 && Math.abs(b.doubleValue()) <= 180) {
            request.put("location", b.doubleValue() + "," + a.doubleValue());
        }
    }

    private Reply cancel(SessionContext context, String sessionId,
                         PendingNavigationSelection selection, String transcript) {
        if (!matchesSelection(context, selection)) {
            return Reply.ofText("地点列表已更新，请重新搜索");
        }
        if (!isTaskDialog(context) && !removeSelection(sessionId, selection)) {
            return Reply.ofText("地点列表已更新或失效，请重新搜索");
        }
        Map<String, SlotValue> slots = new LinkedHashMap<>();
        slots.put("selectionId", SlotValue.stringValue(selection.selectionId()));
        if (isTaskDialog(context)) bindTask(context, slots, "cancel");
        Intent intent = Intent.of("1.0", DOMAIN, CANCEL_INTENT, slots,
                1.0, "navigation.dialog", transcript);
        return Reply.ofAction(intent, "已取消地点搜索");
    }

    private Reply select(SessionContext context, String sessionId,
                         PendingNavigationSelection selection, NavigationCandidate candidate) {
        if (!matchesSelection(context, selection)) {
            return Reply.ofText("地点列表已更新或失效，请重新搜索");
        }
        if (!isTaskDialog(context) && !removeSelection(sessionId, selection)) {
            return Reply.ofText("地点列表已更新或失效，请重新搜索");
        }
        Map<String, SlotValue> slots = new LinkedHashMap<>();
        slots.put("selectionId", SlotValue.stringValue(selection.selectionId()));
        slots.put("candidateId", SlotValue.stringValue(candidate.candidateId()));
        slots.put("poiname", SlotValue.stringValue(candidate.poiname()));
        slots.put("lat", SlotValue.number(candidate.lat()));
        slots.put("lon", SlotValue.number(candidate.lon()));
        if (isTaskDialog(context)) bindTask(context, slots, "select");
        if (selection.previewPreference() != null) {
            slots.put(PREVIEW_PREFERENCE, SlotValue.stringValue(selection.previewPreference().name()));
            Intent intent = Intent.of("1.0", DOMAIN, "preview_routes", slots, 1.0,
                    "navigation.dialog", null);
            return Reply.ofAction(intent, "正在查询去" + candidate.poiname() + "的路线。");
        }
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

    private static boolean isTaskDialog(SessionContext context) {
        return context != null && context.attrs().get("taskDialogVersion") instanceof Number value
                && value.intValue() >= 1;
    }

    private static void bindTask(SessionContext context, Map<String, SlotValue> slots, String operation) {
        slots.remove("taskId");
        slots.remove("taskRevision");
        slots.remove("interactionId");
        slots.put("navigationOperation", SlotValue.stringValue(operation));
        if (context.attrs().get("navigationTaskId") instanceof String id && !id.isBlank()
                && context.attrs().get("navigationTaskRevision") instanceof Number revision
                && context.attrs().get("navigationInteractionId") instanceof String interaction) {
            slots.put("taskId", SlotValue.stringValue(id));
            slots.put("taskRevision", SlotValue.number(revision.doubleValue()));
            slots.put("interactionId", SlotValue.stringValue(interaction));
        }
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
            if (result.size() == 3) break;
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
