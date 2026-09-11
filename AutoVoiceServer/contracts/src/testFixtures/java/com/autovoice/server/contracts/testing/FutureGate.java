package com.autovoice.server.contracts.testing;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * D01b 完成顺序门:控制一组 CompletableFuture 的完成顺序,用于确定性地复现
 * "A 晚于 B 完成"之类的竞态(旧轮晚到、慢候选覆盖等),不依赖 sleep。
 */
public final class FutureGate<T> {

    private final List<CompletableFuture<T>> futures = new ArrayList<>();

    /** 领取一个新未完成 Future,编号为创建顺序。 */
    public CompletableFuture<T> newFuture() {
        CompletableFuture<T> future = new CompletableFuture<>();
        futures.add(future);
        return future;
    }

    /** 完成第 index 个 Future。 */
    public boolean complete(int index, T value) {
        return futures.get(index).complete(value);
    }

    /** 以异常结束第 index 个 Future。 */
    public boolean fail(int index, Throwable cause) {
        return futures.get(index).completeExceptionally(cause);
    }

    /** 按给定顺序依次完成编号对应的 Future。 */
    public void completeInOrder(List<Integer> indexes, T value) {
        for (int index : indexes) {
            complete(index, value);
        }
    }

    /** 按创建顺序完成全部 Future。 */
    public void completeAll(T value) {
        for (int index = 0; index < futures.size(); index++) {
            complete(index, value);
        }
    }

    public int size() {
        return futures.size();
    }
}
