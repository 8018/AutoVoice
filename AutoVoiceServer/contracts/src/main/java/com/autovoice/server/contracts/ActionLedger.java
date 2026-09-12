package com.autovoice.server.contracts;

import java.util.Optional;

/**
 * D07a 动作账本端口(独立 SQLite 业务库,不与遥测库混用)。
 * 首期:签发登记(持久化动作身份)与内部审计查询;不开放客户端查询接口。
 */
public interface ActionLedger extends AutoCloseable {

    ActionLedger NONE = new ActionLedger() {
        @Override public boolean recordDispatch(ActionPlan plan) { return false; }
        @Override public Optional<ActionPlan> findByActionId(String actionId) { return Optional.empty(); }
    };

    /** 幂等签发登记:同 actionId 只记一次;返回 true 表示本次为新写入。 */
    boolean recordDispatch(ActionPlan plan);

    Optional<ActionPlan> findByActionId(String actionId);

    @Override
    default void close() {
    }
}
