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

    /** Shared resolve → model → remember flow used by both Classic and Omni business routes. */
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
                    out.complete(remember(context, reply));
                } catch (Throwable rememberError) {
                    out.completeExceptionally(rememberError);
                }
            }
        });
        return out;
    }
}
