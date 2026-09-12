package com.autovoice.server.contracts;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * D06a 在途轮次索引:以 {@link RequestKey} 登记在途 Future,完成即自动移除;
 * 支持按轮次取消与全量取消。替代仅用 utteranceId 的裸 Map,消除跨设备同名
 * 轮次的登记/取消冲突。
 */
public final class TurnIndex<T> {

    private final Map<RequestKey, CompletableFuture<T>> entries = new ConcurrentHashMap<>();

    /** 登记在途 Future;完成(成功/异常)后自动移除。 */
    public void put(RequestKey key, CompletableFuture<T> future) {
        entries.put(key, future);
        future.whenComplete((value, error) -> entries.remove(key, future));
    }

    /** 取消指定轮次的全部登记(跨会话同名轮次均取消——轮次 ID 为客户端 UUID,冲突概率可忽略)。 */
    public void cancelTurn(String turn) {
        if (turn == null || turn.isBlank()) {
            return;
        }
        entries.forEach((key, future) -> {
            if (key.turn().equals(turn)) {
                entries.remove(key, future);
                future.cancel(true);
            }
        });
    }

    /** 全量取消(资源销毁路径)。 */
    public void cancelAll() {
        entries.forEach((key, future) -> {
            entries.remove(key, future);
            future.cancel(true);
        });
    }

    public int size() {
        return entries.size();
    }
}
