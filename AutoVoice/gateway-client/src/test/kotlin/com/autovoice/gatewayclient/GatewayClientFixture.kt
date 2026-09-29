package com.autovoice.gatewayclient

import com.google.gson.Gson
import okhttp3.OkHttpClient

/** Keep existing MockWebServer tests exercising the real OkHttp adapter. */
@Suppress("FunctionName")
internal fun GatewayClient(
    url: String,
    okHttp: OkHttpClient,
    gson: Gson = Gson(),
    connectTimeoutMs: Long = 5_000,
    backoffBaseMs: Long = 1_000,
    maxRetries: Int = 3,
    deviceId: String? = null,
    authToken: String? = null,
): GatewayClient = GatewayClient(
    url = url,
    transport = OkHttpGatewaySocketTransport(okHttp),
    gson = gson,
    connectTimeoutMs = connectTimeoutMs,
    backoffBaseMs = backoffBaseMs,
    maxRetries = maxRetries,
    deviceId = deviceId,
    authToken = authToken,
)
