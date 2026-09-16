package com.autovoice.server.contracts;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Contract boundary for deterministic, session-scoped navigation candidate selection. */
public interface NavigationDialog {

    NavigationDialog NONE = new NavigationDialog() {
        @Override public Reply remember(SessionContext context, Reply reply) { return reply; }
        @Override public boolean hasPending(SessionContext context) { return false; }
        @Override public Optional<Reply> resolve(SessionContext context, String transcript) {
            return Optional.empty();
        }
    };

    Reply remember(SessionContext context, Reply reply);

    boolean hasPending(SessionContext context);

    Optional<Reply> resolve(SessionContext context, String transcript);

    /**
     * D05a:prepare 只做解析/丰富(生成 selectionId,嵌入回复),不修改共享状态;
     * 候选在输出准入通过后由网关调用 {@link #commit} 条件写入。
     * 默认实现为恒等(无导航能力的装配不受影响)。
     */
    default Reply prepare(SessionContext context, Reply reply) {
        return reply;
    }

    /** D05a:输出准入后的提交点;默认 no-op。实现须幂等(同 selectionId 重复提交无副作用)。 */
    default void commit(SessionContext context, Reply reply) {
    }

    /**
     * D05b 采用确认:客户端会话层显式采用(selectionId 非空)或撤销(空)候选列表,
     * 幂等。旧客户端不发本消息 → 默认已采用(兼容)。
     */
    default void adopt(SessionContext context, String selectionId) {
    }

    /**
     * Shared resolve → model → prepare flow used by both Classic and Omni business routes.
     * 候选只在模型完成后丰富(不落库),落库由输出准入层的 {@link #commit} 完成——
     * 晚到/落败候选未获准输出,不会覆盖已生效的候选列表。
     */
    default CompletableFuture<Reply> complete(
            SessionContext context, String transcript,
            Supplier<CompletableFuture<Reply>> modelCall) {
        CompletableFuture<Reply> source = resolve(context, transcript)
                .map(CompletableFuture::completedFuture)
                .orElseGet(modelCall);
        CompletableFuture<Reply> out = new CompletableFuture<>() {
            @Override public boolean cancel(boolean mayInterruptIfRunning) {
                source.cancel(mayInterruptIfRunning);
                return super.cancel(mayInterruptIfRunning);
            }
        };
        source.whenComplete((reply, error) -> {
            if (out.isDone()) return;
            if (error != null) out.completeExceptionally(error);
            else {
                try {
                    out.complete(prepare(context, reply));
                } catch (Throwable prepareError) {
                    out.completeExceptionally(prepareError);
                }
            }
        });
        return out;
    }
}
