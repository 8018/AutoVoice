package com.autovoice.app

import com.autovoice.gatewayclient.GatewayConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Transport fact only: no request slot, recognition result or pending business reply required. */
internal fun observeConnectionLoss(
    scope: CoroutineScope,
    states: StateFlow<GatewayConnectionState>,
    onLost: () -> Unit,
) = scope.launch(start = CoroutineStart.UNDISPATCHED) {
    var wasReady = false
    states.collect { state ->
        if (wasReady && state != GatewayConnectionState.READY) onLost()
        wasReady = state == GatewayConnectionState.READY
    }
}
