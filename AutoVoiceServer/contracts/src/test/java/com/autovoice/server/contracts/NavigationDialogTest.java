package com.autovoice.server.contracts;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NavigationDialogTest {
    private static final SessionContext CONTEXT =
            new SessionContext("session", "zh-CN", Map.of());

    @Test
    void completeSkipsModelForResolvedDialogReply() {
        AtomicInteger modelCalls = new AtomicInteger();
        NavigationDialog dialog = dialog(Optional.of(Reply.ofText("selected")), new AtomicInteger());

        Reply reply = dialog.complete(CONTEXT, "第一个", () -> {
            modelCalls.incrementAndGet();
            return CompletableFuture.completedFuture(Reply.ofText("model"));
        }).join();

        assertEquals("selected", reply.text());
        assertEquals(0, modelCalls.get());
    }

    @Test
    void completePreparesModelReplyExactlyOnce() {
        AtomicInteger prepares = new AtomicInteger();
        NavigationDialog dialog = dialog(Optional.empty(), prepares);

        Reply reply = dialog.complete(CONTEXT, "导航去机场",
                () -> CompletableFuture.completedFuture(Reply.ofText("model"))).join();

        assertEquals("prepared", reply.text());
        assertEquals(1, prepares.get());
    }

    @Test
    void completePropagatesPrepareFailureInsteadOfLeavingCallerPending() {
        NavigationDialog dialog = new NavigationDialog() {
            @Override public Reply remember(SessionContext context, Reply reply) { return reply; }
            @Override public Reply prepare(SessionContext context, Reply reply) {
                throw new IllegalStateException("bad candidates");
            }
            @Override public boolean hasPending(SessionContext context) { return false; }
            @Override public Optional<Reply> resolve(SessionContext context, String transcript) {
                return Optional.empty();
            }
        };

        var result = dialog.complete(CONTEXT, "导航去机场",
                () -> CompletableFuture.completedFuture(Reply.ofText("model")));

        assertEquals("bad candidates", assertThrows(java.util.concurrent.CompletionException.class,
                result::join).getCause().getMessage());
    }

    private static NavigationDialog dialog(Optional<Reply> resolved, AtomicInteger prepares) {
        return new NavigationDialog() {
            @Override public Reply remember(SessionContext context, Reply reply) { return reply; }
            @Override public Reply prepare(SessionContext context, Reply reply) {
                if ("model".equals(reply.text())) {
                    prepares.incrementAndGet();
                    return Reply.ofText("prepared");
                }
                return reply; // 已解析的回复无需丰富
            }
            @Override public boolean hasPending(SessionContext context) { return resolved.isPresent(); }
            @Override public Optional<Reply> resolve(SessionContext context, String transcript) {
                return resolved;
            }
        };
    }
}
