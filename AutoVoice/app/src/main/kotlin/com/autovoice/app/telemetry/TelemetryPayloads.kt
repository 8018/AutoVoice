package com.autovoice.app.telemetry

import com.google.gson.annotations.SerializedName

/** Explicit wire models shared by the collector and Retrofit adapter. */
internal data class TelemetryEventPayload(
    @SerializedName("stage") val stage: String,
    @SerializedName("tsMs") val tsMs: Long,
    @SerializedName("level") val level: String,
    @SerializedName("payload") val payload: Map<String, Any?>,
)

internal data class TelemetryRoundPayload(
    @SerializedName("utteranceId") val utteranceId: String,
    @SerializedName("sessionId") val sessionId: String,
    @SerializedName("deviceId") val deviceId: String,
    @SerializedName("source") val source: String,
    @SerializedName("startMs") val startMs: Long,
    @SerializedName("endMs") val endMs: Long,
    @SerializedName("events") val events: List<TelemetryEventPayload>,
)

internal data class TelemetryEventBatch(
    @SerializedName("utteranceId") val utteranceId: String,
    @SerializedName("events") val events: List<TelemetryEventPayload>,
)
