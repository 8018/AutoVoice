package com.autovoice.server.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** D06a 在途轮次索引:跨设备同名轮次隔离、完成自动移除、取消语义。 */
class TurnIndexTest {

    @Test
    void sameTurnAcrossDevicesIsIsolatedOnCancel() {
        TurnIndex<String> index = new TurnIndex<>();
        CompletableFuture<String> deviceA = new CompletableFuture<>();
        CompletableFuture<String> deviceB = new CompletableFuture<>();
        index.put(RequestKey.of("device-a", "sess-1", "turn-7", ""), deviceA);
        index.put(RequestKey.of("device-b", "sess-2", "turn-7", ""), deviceB);
        assertEquals(2, index.size());

        index.cancelTurn("turn-7");
        assertTrue(deviceA.isCancelled());
        assertTrue(deviceB.isCancelled(), "同名轮次取消应命中全部会话(轮次 ID 为 UUID)");
        assertEquals(0, index.size());
    }

    @Test
    void completionRemovesEntryAutomatically() {
        TurnIndex<String> index = new TurnIndex<>();
        RequestKey key = RequestKey.of("device-a", "sess-1", "turn-1", "");
        CompletableFuture<String> future = new CompletableFuture<>();
        index.put(key, future);
        future.complete("done");
        assertEquals(0, index.size(), "完成后应自动移除登记");
    }

    @Test
    void cancelAllClearsEverything() {
        TurnIndex<String> index = new TurnIndex<>();
        index.put(RequestKey.of("a", "s1", "t1", ""), new CompletableFuture<>());
        index.put(RequestKey.of("b", "s2", "t2", ""), new CompletableFuture<>());
        index.cancelAll();
        assertEquals(0, index.size());
    }

    @Test
    void cancelTurnIgnoresBlankTurn() {
        TurnIndex<String> index = new TurnIndex<>();
        CompletableFuture<String> future = new CompletableFuture<>();
        index.put(RequestKey.of("a", "s1", "t1", ""), future);
        index.cancelTurn(" ");
        assertFalse(future.isCancelled());
        assertEquals(1, index.size());
    }
}
