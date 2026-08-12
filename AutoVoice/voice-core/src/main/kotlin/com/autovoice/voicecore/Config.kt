package com.autovoice.voicecore

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * demo 单文件配置，字段与 shared/contracts/config.schema.json 一一对应。
 * 解析规则：
 *  - required = [mode, cloud, local]，缺失即抛 [IllegalArgumentException]；
 *  - cloud.enabled / cloud.waitMs 必读；
 *  - 未知字段忽略（宽松解析）。
 */
data class DemoConfig(
    val mode: String,
    val vad: VadConfig,
    val ecnr: String,
    val local: LocalConfig,
    val cloud: CloudConfig,
    val mock: MockConfig,
) {
    companion object {
        fun fromJson(json: String): DemoConfig {
            val root = JsonParser.parseString(json).asJsonObject
            val mode = root.requiredString("mode")
            val cloud = root.requiredObject("cloud")
            val local = root.requiredObject("local")
            val vad = root.get("vad")?.takeIf { it.isJsonObject }?.asJsonObject
            val ecnr = root.get("ecnr")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: ""
            val mock = root.get("mock")?.takeIf { it.isJsonObject }?.asJsonObject

            return DemoConfig(
                mode = mode,
                vad = VadConfig(
                    threshold = vad?.optNumber("threshold") ?: VadConfig.DEFAULT_THRESHOLD,
                    minSpeechMs = vad?.optLong("minSpeechMs") ?: VadConfig.DEFAULT_MIN_SPEECH_MS,
                    minSilenceMs = vad?.optLong("minSilenceMs") ?: VadConfig.DEFAULT_MIN_SILENCE_MS,
                ),
                ecnr = ecnr,
                local = LocalConfig(
                    asr = local.requiredString("asr"),
                    nlu = local.requiredString("nlu"),
                ),
                cloud = CloudConfig(
                    enabled = cloud.requiredBoolean("enabled"),
                    gatewayUrl = cloud.optString("gatewayUrl") ?: "",
                    waitMs = cloud.requiredLong("waitMs"),
                    // 多设备加固 M5：网关鉴权凭据（auth-enabled 时必填；null → hello 不带）
                    deviceId = cloud.optString("deviceId"),
                    authToken = cloud.optString("authToken"),
                ),
                mock = MockConfig(
                    executor = mock?.optBoolean("executor") ?: false,
                ),
            )
        }
    }
}

data class VadConfig(
    val threshold: Double = DEFAULT_THRESHOLD,
    val minSpeechMs: Long = DEFAULT_MIN_SPEECH_MS,
    val minSilenceMs: Long = DEFAULT_MIN_SILENCE_MS,
) {
    companion object {
        const val DEFAULT_THRESHOLD = 0.5
        const val DEFAULT_MIN_SPEECH_MS = 64L
        const val DEFAULT_MIN_SILENCE_MS = 960L
    }
}

data class LocalConfig(
    val asr: String,
    val nlu: String,
)

data class CloudConfig(
    val enabled: Boolean,
    val gatewayUrl: String = "",
    val waitMs: Long,
    /** 网关鉴权设备标识（M5；服务器 auth-enabled 时必填，null → hello 帧不带该字段）。 */
    val deviceId: String? = null,
    /** 网关鉴权令牌（M5；与服务器 devices 表一致，值会进 APK，demo 静态凭据可接受）。 */
    val authToken: String? = null,
)

data class MockConfig(
    val executor: Boolean = false,
)

// ---------- 宽松 JSON 读取辅助 ----------

private fun JsonObject.requiredString(name: String): String {
    val el = get(name)
    require(el != null && el.isJsonPrimitive && el.asJsonPrimitive.isString) {
        "config: missing or invalid required string field '$name'"
    }
    return el.asString
}

private fun JsonObject.requiredObject(name: String): JsonObject {
    val el = get(name)
    require(el != null && el.isJsonObject) { "config: missing or invalid required object field '$name'" }
    return el.asJsonObject
}

private fun JsonObject.requiredBoolean(name: String): Boolean {
    val el = get(name)
    require(el != null && el.isJsonPrimitive) { "config: missing or invalid required boolean field '$name'" }
    return el.asBoolean
}

private fun JsonObject.requiredLong(name: String): Long {
    val el = get(name)
    require(el != null && el.isJsonPrimitive) { "config: missing or invalid required long field '$name'" }
    return el.asLong
}

private fun JsonObject.optNumber(name: String): Double? =
    get(name)?.takeIf { it.isJsonPrimitive }?.asDouble

private fun JsonObject.optLong(name: String): Long? =
    get(name)?.takeIf { it.isJsonPrimitive }?.asLong

private fun JsonObject.optBoolean(name: String): Boolean? =
    get(name)?.takeIf { it.isJsonPrimitive }?.asBoolean

private fun JsonObject.optString(name: String): String? =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
