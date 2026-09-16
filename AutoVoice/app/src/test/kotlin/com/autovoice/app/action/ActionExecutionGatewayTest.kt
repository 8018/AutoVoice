package com.autovoice.app.action

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ActionExecutionGatewayTest {

    @Test
    fun blankTurnCannotExecute() {
        val calls = AtomicInteger()
        assertEquals(
            ActionExecutionGateway.Result.INVALID_TURN,
            ActionExecutionGateway().execute("") { calls.incrementAndGet(); true },
        )
        assertEquals(0, calls.get())
    }

    @Test
    fun currentTurnExecutesOnlyOnce() {
        val gateway = ActionExecutionGateway()
        val calls = AtomicInteger()
        assertEquals(ActionExecutionGateway.Result.APPLIED,
            gateway.execute("turn-1") { calls.incrementAndGet(); true })
        assertEquals(ActionExecutionGateway.Result.DUPLICATE,
            gateway.execute("turn-1") { calls.incrementAndGet(); true })
        assertEquals(1, calls.get())
    }

    @Test
    fun concurrentCallbacksHaveOneOwner() {
        val gateway = ActionExecutionGateway()
        val calls = AtomicInteger()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = List(2) {
                pool.submit<ActionExecutionGateway.Result> {
                    start.await()
                    gateway.execute("turn-race") { calls.incrementAndGet(); true }
                }
            }
            start.countDown()
            val completed = results.map { it.get(2, TimeUnit.SECONDS) }
            assertEquals(1, completed.count { it == ActionExecutionGateway.Result.APPLIED })
            assertEquals(1, completed.count { it == ActionExecutionGateway.Result.DUPLICATE })
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1, calls.get())
    }

    @Test
    fun failureDoesNotReopenTurnSlot() {
        val gateway = ActionExecutionGateway()
        assertEquals(ActionExecutionGateway.Result.FAILED, gateway.execute("turn-failed") { false })
        assertEquals(ActionExecutionGateway.Result.DUPLICATE, gateway.execute("turn-failed") { true })
    }

    @Test
    fun sameWordsInANewTurnAreANewAction() {
        val gateway = ActionExecutionGateway()
        val calls = AtomicInteger()
        assertEquals(ActionExecutionGateway.Result.APPLIED,
            gateway.execute("turn-1") { calls.incrementAndGet(); true })
        assertEquals(ActionExecutionGateway.Result.APPLIED,
            gateway.execute("turn-2") { calls.incrementAndGet(); true })
        assertEquals(2, calls.get())
    }

    @Test
    fun capacityMustBePositive() {
        assertThrows(IllegalArgumentException::class.java) { ActionExecutionGateway(0) }
    }
}
