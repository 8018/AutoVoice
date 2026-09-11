package com.autovoice.voicecore

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * demo 单文件配置，字段与 shared/contracts/config.schema.json 一一对应。
 * 解析规则：
 *  - required = [mode, cloud, local]，缺失即抛 [IllegalArgumentException]；
 *  - cloud.enabled / cloud.gatewayUrl / cloud.waitMs 必读；
 *  - 解析后立即校验当前构建实际支持的 provider 与参数范围；
 *  - 未知字段忽略（宽松解析）。
 */
data class DemoConfig(
    val mode: String,
    val vad: VadConfig,
    val ecnr: String,
    val local: LocalConfig,
    val cloud: CloudConfig,
    val mock: MockConfig,
    /** Optional PCM asset used only for deterministic device/demo testing. */
    val testAudio: String? = null,
) {
    companion object {
        fun fromJson(json: String): DemoConfig {
            val root = JsonParser.parseString(json).asJsonObject
            val mode = root.requiredString("mode")
            val cloud = root.requiredObject("cloud")
            val local = root.requiredObject("local")
            val vad = root.optObject("vad")
            val ecnr = root.optString("ecnr") ?: ECNR_RNNOISE
            val mock = root.optObject("mock")

            return DemoConfig(
                mode = mode,
                vad = VadConfig(
                    threshold = vad?.optNumber("threshold") ?: VadConfig.DEFAULT_THRESHOLD,
                    minSpeechMs = vad?.optLong("minSpeechMs") ?: VadConfig.DEFAULT_MIN_SPEECH_MS,
                    minSilenceMs = vad?.optLong("minSilenceMs") ?: VadConfig.DEFAULT_MIN_SILENCE_MS,
                ),
                // 旧配置未声明时保持历史上的 RNNoise 实际行为。
                ecnr = ecnr,
                local = LocalConfig(
                    asr = local.requiredString("asr"),
                    nlu = local.requiredString("nlu"),
                ),
                cloud = CloudConfig(
                    enabled = cloud.requiredBoolean("enabled"),
                    gatewayUrl = cloud.requiredString("gatewayUrl"),
                    waitMs = cloud.requiredLong("waitMs"),
                    // 多设备加固 M5：网关鉴权凭据（auth-enabled 时必填；null → hello 不带）
                    deviceId = cloud.optString("deviceId"),
                    authToken = cloud.optString("authToken"),
                    // T6 遥测（数据平台一期）：cloud.telemetry 可选段，未配置 → null（遥测关闭）
                    telemetry = cloud.optObject("telemetry")
                        ?.let { t ->
                            TelemetryConfig(
                                enabled = t.optBoolean("enabled") ?: true,
                                // url 空白 → null：装配处回落 gatewayUrl 推导
                                url = t.optString("url")?.takeIf { it.isNotBlank() },
                            )
                        },
                ),
                mock = MockConfig(
                    executor = mock?.optBoolean("executor") ?: false,
                ),
                testAudio = root.optString("testAudio")?.takeIf { it.isNotBlank() },
            ).validateForRuntime()
        }

        const val ECNR_RNNOISE = "rnnoise"
        const val ECNR_NONE = "none"
        const val LOCAL_ASR_IFLYTEK = "iflytek.offline"
        const val LOCAL_ASR_FAKE = "iflytek.fake-cmd"
        const val LOCAL_NLU_RULE = "rule.nlu"
    }
}

data class VadConfig(
    val threshold: Double = DEFAULT_THRESHOLD,
    val minSpeechMs: Long = DEFAULT_MIN_SPEECH_MS,
    val minSilenceMs: Long = DEFAULT_MIN_SILENCE_MS,
) {
    init {
        require(threshold.isFinite() && threshold in 0.0..1.0) {
            "config: vad.threshold must be a finite number in [0, 1], got $threshold"
        }
        require(minSpeechMs in MIN_DURATION_MS..MAX_DURATION_MS) {
            "config: vad.minSpeechMs must be in [$MIN_DURATION_MS, $MAX_DURATION_MS], got $minSpeechMs"
        }
        require(minSilenceMs in MIN_DURATION_MS..MAX_DURATION_MS) {
            "config: vad.minSilenceMs must be in [$MIN_DURATION_MS, $MAX_DURATION_MS], got $minSilenceMs"
        }
    }

    companion object {
        const val DEFAULT_THRESHOLD = 0.5
        const val DEFAULT_MIN_SPEECH_MS = 64L
        const val DEFAULT_MIN_SILENCE_MS = 960L
        const val MIN_DURATION_MS = 1L
        const val MAX_DURATION_MS = 60_000L
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
    /** 链路数据平台（T6）：null → 端侧 TelemetryClient 不启用（全 no-op）。 */
    val telemetry: TelemetryConfig? = null,
)

/** 链路数据平台配置（T6）：enabled 控制端侧上报；url 为 HTTP 基址，空白 → 由 gatewayUrl 推导。 */
data class TelemetryConfig(
    val enabled: Boolean = true,
    val url: String? = null,
)

data class MockConfig(
    val executor: Boolean = false,
)

/**
 * Validates the provider matrix implemented by the current Android composition root.
 * Kept separate from data-class construction so isolated core tests may still use test doubles;
 * JSON configuration and production assembly both call this guard.
 */
fun DemoConfig.validateForRuntime(): DemoConfig {
    require(mode == "full" || mode == "offline") {
        "config: unsupported mode '$mode'; supported: full, offline"
    }
    require(ecnr == DemoConfig.ECNR_RNNOISE || ecnr == DemoConfig.ECNR_NONE) {
        "config: unsupported ecnr '$ecnr'; supported: rnnoise, none"
    }
    require(local.asr == DemoConfig.LOCAL_ASR_IFLYTEK || local.asr == DemoConfig.LOCAL_ASR_FAKE) {
        "config: unsupported local.asr '${local.asr}'; supported: iflytek.offline, iflytek.fake-cmd"
    }
    require(local.nlu == DemoConfig.LOCAL_NLU_RULE) {
        "config: unsupported local.nlu '${local.nlu}'; supported: rule.nlu"
    }
    require(cloud.waitMs > 0) {
        "config: cloud.waitMs must be positive, got ${cloud.waitMs}"
    }
    require(!cloud.enabled || cloud.gatewayUrl.isNotBlank()) {
        "config: cloud.gatewayUrl must not be blank when cloud.enabled=true"
    }
    require(!mock.executor) {
        "config: mock.executor=true is reserved but not implemented; keep it false"
    }
    return this
}

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

private fun JsonObject.optObject(name: String): JsonObject? {
    val el = get(name) ?: return null
    require(el.isJsonObject) { "config: invalid optional object field '$name'" }
    return el.asJsonObject
}

private fun JsonObject.requiredBoolean(name: String): Boolean {
    val el = get(name)
    require(el != null && el.isJsonPrimitive && el.asJsonPrimitive.isBoolean) {
        "config: missing or invalid required boolean field '$name'"
    }
    return el.asBoolean
}

private fun JsonObject.requiredLong(name: String): Long {
    val el = get(name)
    require(el != null && el.isJsonPrimitive && el.asJsonPrimitive.isNumber) {
        "config: missing or invalid required long field '$name'"
    }
    return el.asLong
}

private fun JsonObject.optNumber(name: String): Double? {
    val el = get(name) ?: return null
    require(el.isJsonPrimitive && el.asJsonPrimitive.isNumber) {
        "config: invalid optional number field '$name'"
    }
    return el.asDouble
}

private fun JsonObject.optLong(name: String): Long? {
    val el = get(name) ?: return null
    require(el.isJsonPrimitive && el.asJsonPrimitive.isNumber) {
        "config: invalid optional long field '$name'"
    }
    return el.asLong
}

private fun JsonObject.optBoolean(name: String): Boolean? {
    val el = get(name) ?: return null
    require(el.isJsonPrimitive && el.asJsonPrimitive.isBoolean) {
        "config: invalid optional boolean field '$name'"
    }
    return el.asBoolean
}

private fun JsonObject.optString(name: String): String? {
    val el = get(name) ?: return null
    require(el.isJsonPrimitive && el.asJsonPrimitive.isString) {
        "config: invalid optional string field '$name'"
    }
    return el.asString
}
