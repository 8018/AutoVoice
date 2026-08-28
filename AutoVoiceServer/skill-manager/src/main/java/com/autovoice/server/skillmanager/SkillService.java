package com.autovoice.server.skillmanager;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/** skill 业务：CRUD、启停、脱敏（管理端掩码 / 网关拉取明文）、变更后 webhook 通知。 */
public final class SkillService {

    static final String MASKED = "****";

    private final SqliteSkillStore store;
    private final SkillWebhookNotifier notifier;
    private final LongSupplier clock;

    public SkillService(SqliteSkillStore store, SkillWebhookNotifier notifier, LongSupplier clock) {
        this.store = store;
        this.notifier = notifier;
        this.clock = clock;
    }

    public List<SkillResponse> list(boolean gatewayView) {
        List<SkillResponse> out = new ArrayList<>();
        for (SkillRecord r : store.findAll(gatewayView)) {   // 网关视图只拿 enabled
            out.add(toResponse(r, gatewayView));
        }
        return out;
    }

    public SkillResponse get(String id, boolean gatewayView) {
        SkillRecord r = store.findById(id);
        return r == null ? null : toResponse(r, gatewayView);
    }

    public boolean exists(String id) {
        return store.findById(id) != null;
    }

    public SkillResponse create(SkillRequest req) {
        SkillRecord r = new SkillRecord(req.id(), str(req.name()), str(req.description()), scope(req.scope()),
                str(req.mcpUrl()), str(req.authHeader()), str(req.authValue()),
                req.toolsJson() == null ? "[]" : req.toolsJson(),
                req.enabled() != null && req.enabled(), clock.getAsLong());
        store.upsert(r);
        notifier.notifySkillChanged(r.id());
        return toResponse(r, false);
    }

    public SkillResponse update(String id, SkillRequest req) {
        SkillRecord old = store.findById(id);
        String targetScope = req.scope() == null || req.scope().isBlank()
                ? (old == null ? "llm" : old.scope()) : scope(req.scope());
        SkillRecord r = new SkillRecord(id, str(req.name()), str(req.description()), targetScope,
                str(req.mcpUrl()), str(req.authHeader()),
                // 留空（null/空串）= 保留旧值
                (req.authValue() == null || req.authValue().isBlank())
                        ? (old == null ? "" : old.authValue())
                        : req.authValue(),
                req.toolsJson() == null ? "[]" : req.toolsJson(),
                req.enabled() != null ? req.enabled() : (old != null && old.enabled()),
                clock.getAsLong());
        store.upsert(r);
        notifier.notifySkillChanged(id);
        return toResponse(r, false);
    }

    public void delete(String id) {
        store.delete(id);
        notifier.notifySkillChanged(id);
    }

    public SkillResponse setEnabled(String id, boolean enabled) {
        SkillRecord old = store.findById(id);
        if (old == null) {
            // controller 已守卫 exists()，此为防御：NotFound 语义，避免静默 NPE
            throw new IllegalStateException("skill not found: " + id);
        }
        SkillRecord r = new SkillRecord(id, old.name(), old.description(), old.scope(), old.mcpUrl(),
                old.authHeader(), old.authValue(), old.toolsJson(), enabled, clock.getAsLong());
        store.upsert(r);
        notifier.notifySkillChanged(id);
        return toResponse(r, false);
    }

    /** 脱敏：网关视图（service token 拉取）返回明文；管理端视图非空即 "****"。 */
    public SkillResponse toResponse(SkillRecord r, boolean gatewayView) {
        String authValue = gatewayView ? r.authValue()
                : (r.authValue() == null || r.authValue().isBlank() ? "" : MASKED);
        return new SkillResponse(r.id(), r.name(), r.description(), r.scope(), r.mcpUrl(),
                r.authHeader(), authValue, r.toolsJson(), r.enabled(), r.updatedAt());
    }

    private static String scope(String value) {
        return "chat".equals(value) ? "chat" : "llm";
    }

    private static String str(String value) {
        return value == null ? "" : value;
    }
}
