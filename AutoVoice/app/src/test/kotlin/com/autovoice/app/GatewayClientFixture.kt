package com.autovoice.app

import com.autovoice.gatewayclient.GatewayClient
import com.autovoice.gatewayclient.OkHttpGatewaySocketTransport
import com.google.gson.Gson
import okhttp3.OkHttpClient

/** App-level gateway tests also use the production WebSocket adapter. */
@Suppress("FunctionName")
internal fun GatewayClient(url: String, okHttp: OkHttpClient, gson: Gson = Gson()): GatewayClient =
    GatewayClient(url, OkHttpGatewaySocketTransport(okHttp), gson)
