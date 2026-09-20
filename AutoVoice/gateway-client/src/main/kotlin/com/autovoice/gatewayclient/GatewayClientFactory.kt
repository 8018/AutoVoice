package com.autovoice.gatewayclient

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/** Transport lifecycle policy. No speech or business protocol settings belong here. */
data class GatewayConnectionPolicy(
    val pingIntervalMs: Long = 15_000,
    val connectTimeoutMs: Long = 3_000,
    val reconnectAttempts: Int = 0,
    val reconnectBackoffMs: Long = 1_000,
)

object GatewayClientFactory {
    fun create(
        url: String,
        deviceId: String?,
        authToken: String?,
        policy: GatewayConnectionPolicy = GatewayConnectionPolicy(),
    ): GatewayClient = GatewayClient(
        url = url,
        okHttp = OkHttpClient.Builder()
            .pingInterval(policy.pingIntervalMs, TimeUnit.MILLISECONDS)
            .build(),
        deviceId = deviceId,
        authToken = authToken,
        connectTimeoutMs = policy.connectTimeoutMs,
        backoffBaseMs = policy.reconnectBackoffMs,
        maxRetries = policy.reconnectAttempts,
    )
}
