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

        assertEquals("remembered", reply.text());
        assertEquals(0, modelCalls.get());
    }

    @Test
    void completeRemembersModelReplyExactlyOnce() {
        AtomicInteger remembers = new AtomicInteger();
        NavigationDialog dialog = dialog(Optional.empty(), remembers);

        Reply reply = dialog.complete(CONTEXT, "导航去机场",
                () -> CompletableFuture.completedFuture(Reply.ofText("model"))).join();

        assertEquals("remembered", reply.text());
        assertEquals(1, remembers.get());
    }

    @Test
    void completePropagatesRememberFailureInsteadOfLeavingCallerPending() {
        NavigationDialog dialog = new NavigationDialog() {
            @Override public Reply remember(SessionContext context, Reply reply) {
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

    private static NavigationDialog dialog(Optional<Reply> resolved, AtomicInteger remembers) {
        return new NavigationDialog() {
            @Override public Reply remember(SessionContext context, Reply reply) {
                remembers.incrementAndGet();
                return Reply.ofText("remembered");
            }
            @Override public boolean hasPending(SessionContext context) { return resolved.isPresent(); }
            @Override public Optional<Reply> resolve(SessionContext context, String transcript) {
                return resolved;
            }
        };
    }
}
