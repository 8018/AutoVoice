package com.autovoice.app

import com.autovoice.gatewayclient.GatewayConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConnectionLossObserverTest {
    @Test fun `idle connection loss closes list without a pending reply and ready does not replay it`() = runTest {
        val states = MutableStateFlow(GatewayConnectionState.DISCONNECTED)
        val session = NavigationSession()
        var losses = 0
        val observer = observeConnectionLoss(backgroundScope, states) { losses++; session.abortSelection() }
        states.value = GatewayConnectionState.CONNECTING; runCurrent()
        assertEquals(0, losses)
        states.value = GatewayConnectionState.READY; runCurrent()
        session.offer(listOf(NavigationExecutor.NavigationCandidate("airport", 30.0, 104.0)), "s", "t")
        states.value = GatewayConnectionState.DISCONNECTED; runCurrent()
        assertTrue(session.snapshot.candidates.isEmpty())
        assertEquals(1, losses)
        states.value = GatewayConnectionState.CONNECTING; runCurrent()
        states.value = GatewayConnectionState.DISCONNECTED; runCurrent()
        assertEquals(1, losses)
        states.value = GatewayConnectionState.READY; runCurrent()
        assertTrue(session.snapshot.candidates.isEmpty())
        observer.cancel()
    }
}
