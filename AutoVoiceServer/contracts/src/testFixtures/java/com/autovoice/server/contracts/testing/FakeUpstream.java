package com.autovoice.server.contracts.testing;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * D01b 可控上游:测试按需推送条目、模拟流中/调用中上游断连。
 * 订阅方在 {@link #disconnect} 时收到断连原因;断连后 {@link #emit} 直接丢弃。
 */
public final class FakeUpstream<T> {

    private final List<Consumer<T>> itemListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<Throwable>> disconnectListeners = new CopyOnWriteArrayList<>();
    private volatile boolean closed = false;

    public void subscribe(Consumer<T> onItem, Consumer<Throwable> onDisconnect) {
        itemListeners.add(onItem);
        disconnectListeners.add(onDisconnect);
    }

    /** 推送条目;已断连则丢弃。 */
    public void emit(T item) {
        if (closed) {
            return;
        }
        for (Consumer<T> listener : itemListeners) {
            listener.accept(item);
        }
    }

    /** 模拟上游断连,向所有订阅方广播原因。 */
    public void disconnect(Throwable cause) {
        if (closed) {
            return;
        }
        closed = true;
        for (Consumer<Throwable> listener : disconnectListeners) {
            listener.accept(cause);
        }
    }

    public boolean isClosed() {
        return closed;
    }
}
