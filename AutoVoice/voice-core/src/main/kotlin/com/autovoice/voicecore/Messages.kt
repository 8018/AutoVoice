package com.autovoice.voicecore

import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName

/**
 * 槽位值，序列化输出 `{"type":...,"value":...}`，字段名与
 * shared/contracts/intent.schema.json 的 slots.additionalProperties 对齐。
 */
sealed class SlotValue {
    /** schema 中的 type 枚举值：number / enum / string / boolean。 */
    abstract val type: String

    /** schema 中的 value，无 backing field，不参与 Gson 序列化。 */
    abstract val value: Any

    /** `{"type":"number","value":<double>}`，JSON 数字无引号。 */
    data class Number(
        @SerializedName("value") val v: Double,
    ) : SlotValue() {
        override val type: String = "number"
        override val value: Any get() = v
    }

    /** `{"type":"enum","value":"<string>"}`。 */
    data class EnumValue(
        @SerializedName("value") val v: String,
    ) : SlotValue() {
        override val type: String = "enum"
        override val value: Any get() = v
    }

    /** `{"type":"string","value":"<string>"}`。 */
    data class StringValue(
        @SerializedName("value") val v: String,
    ) : SlotValue() {
        override val type: String = "string"
        override val value: Any get() = v
    }

    /** `{"type":"boolean","value":<bool>}`。 */
    data class Bool(
        @SerializedName("value") val v: Boolean,
    ) : SlotValue() {
        override val type: String = "boolean"
        override val value: Any get() = v
    }
}

/**
 * 规范化意图，字段名与 shared/contracts/intent.schema.json 逐字对齐。
 * rawSemantic 为 null 时序列化省略该字段（Gson 默认跳过 null）。
 */
data class Intent(
    val schemaVersion: String,
    val domain: String,
    val intent: String,
    val slots: Map<String, SlotValue>,
    val confidence: Double,
    val source: String,
    val rawSemantic: String? = null,
) {
    /** 未识别意图（兜底路由）标记。 */
    fun isUnknown(): Boolean = intent == INTENT_UNKNOWN

    companion object {
        const val INTENT_UNKNOWN = "unknown"

        /** 构造一个未识别意图（domain 传递用户领域，其余字段为约定兜底值）。 */
        fun unknown(domain: String): Intent =
            Intent(
                schemaVersion = "1.0",
                domain = domain,
                intent = INTENT_UNKNOWN,
                slots = emptyMap(),
                confidence = 1.0,
                source = "builtin.unknown",
                rawSemantic = null,
            )
    }
}

/**
 * 网关下行回复。网关下发的音频回复统一 [AudioReply]（云端 TTS 后 kind 恒为 audio，
 * intent 存在时附带，供执行器消费）；intent 为 null 时序列化省略该字段。
 *
 * [asrText]（Task 61）：云端 ASR 识别文本，随 reply 下行——端侧云端胜出时据此把
 * 语义结果里的识别文本写进识别区；本地胜出时该字段为空（不覆盖本地识别文本）。
 */
sealed class Reply {
    /** 网关 payload 中的 kind 字段。 */
    abstract val kind: String

    /** 云端 ASR 识别文本（无则空串，本地胜出/未携带时不覆盖识别区）。 */
    abstract val asrText: String
}

/** 纯文本回复。 */
data class TextReply(
    val text: String,
    override val asrText: String = "",
) : Reply() {
    override val kind: String = "text"
}

/** 音频回复（云端 TTS 下行）。 */
data class AudioReply(
    val mime: String,
    val data: ByteArray,
    val speakText: String = "",
    val intent: Intent? = null,
    override val asrText: String = "",
) : Reply() {
    override val kind: String = "audio"
}

/** 意图执行回复（供端侧执行器消费）。 */
data class ActionReply(
    val intent: Intent,
    val speakText: String,
    override val asrText: String = "",
) : Reply() {
    override val kind: String = "action"
}

/** 仲裁器单条决策日志条目（云端 arbitrer 下行 / 端侧记录）。 */
data class DecisionEntry(
    val arbiter: String,
    val route: String,
    val reason: String,
    val utteranceId: String,
    val timestampMs: Long,
)

/** 网关消息信封：type + payload，与 shared/contracts 的网关协议对齐。 */
data class GatewayMessage(
    val type: String,
    val payload: JsonObject,
)
