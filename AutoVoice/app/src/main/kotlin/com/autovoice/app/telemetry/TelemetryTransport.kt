package com.autovoice.app.telemetry

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

/** Only the telemetry HTTP contract lives here; Gateway WebSocket traffic is separate. */
internal interface TelemetryTransport {
    suspend fun postRound(round: TelemetryRoundPayload): Int
    suspend fun postEvents(batch: TelemetryEventBatch): Int
    suspend fun postAudio(utteranceId: String, deviceId: String?, pcm: ByteArray): Int
}

private interface TelemetryApi {
    @POST("api/telemetry/round")
    suspend fun postRound(@Body body: TelemetryRoundPayload): Response<Unit>

    @POST("api/telemetry/events")
    suspend fun postEvents(@Body body: TelemetryEventBatch): Response<Unit>

    @Multipart
    @POST("api/telemetry/audio")
    suspend fun postAudio(
        @Part("utteranceId") utteranceId: RequestBody,
        @Part("deviceId") deviceId: RequestBody?,
        @Part file: MultipartBody.Part,
    ): Response<Unit>
}

/** Retrofit owns REST declarations and JSON conversion; OkHttp owns transport policy. */
internal class RetrofitTelemetryTransport(okHttp: OkHttpClient, baseUrl: String) : TelemetryTransport {
    private val api = Retrofit.Builder()
        .baseUrl(baseUrl.trimEnd('/') + "/")
        .client(okHttp)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(TelemetryApi::class.java)

    override suspend fun postRound(round: TelemetryRoundPayload): Int =
        api.postRound(round).code()

    override suspend fun postEvents(batch: TelemetryEventBatch): Int =
        api.postEvents(batch).code()

    override suspend fun postAudio(utteranceId: String, deviceId: String?, pcm: ByteArray): Int {
        val file = MultipartBody.Part.createFormData(
            "file",
            "$utteranceId.pcm",
            pcm.toRequestBody(OCTET_STREAM),
        )
        return api.postAudio(
            utteranceId.toRequestBody(),
            deviceId?.takeIf { it.isNotBlank() }?.toRequestBody(),
            file,
        ).code()
    }

    private companion object {
        val OCTET_STREAM = "application/octet-stream".toMediaType()
    }
}
