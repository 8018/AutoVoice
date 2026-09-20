package com.autovoice.gatewayclient

import com.autovoice.voicecore.ActionReply
import com.autovoice.voicecore.AudioReply
import com.autovoice.voicecore.AudioStreamEnd
import com.autovoice.voicecore.Intent
import com.autovoice.voicecore.Reply
import com.autovoice.voicecore.SlotValue
import com.autovoice.voicecore.StreamingAudioReply
import com.autovoice.voicecore.TextReply
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.util.Base64
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.ReceiveChannel

/** Stateless protocol payload decoder, separate from WebSocket connection and lifecycle control. */
class GatewayPayloadParser {
    fun tts(payload: JsonObject): AudioReply? {
        val mime = payload["mime"].stringOrNull() ?: return null
        val data = decode(payload["dataBase64"].stringOrNull() ?: return null) ?: return null
        return AudioReply(mime, data, payload["text"].stringOrNull() ?: "")
    }

    fun reply(payload: JsonObject): Reply? {
        val kind = payload["kind"].stringOrNull() ?: return null
        val asrText = payload["asrText"].stringOrNull() ?: ""
        return when (kind) {
            "text" -> TextReply(payload["text"].stringOrNull() ?: return null, asrText)
            "audio" -> AudioReply(
                mime = payload["mime"].stringOrNull() ?: return null,
                data = decode(payload["dataBase64"].stringOrNull() ?: return null) ?: return null,
                speakText = payload["speakText"].stringOrNull() ?: "",
                intent = intent(payload["intent"]),
                asrText = asrText,
                actionId = payload["actionId"].stringOrNull() ?: "",
                actionExpiresAtMs = payload["actionExpiresAtMs"].numberOrNull()?.toLong() ?: 0,
            )
            "action" -> ActionReply(
                intent = intent(payload["intent"]) ?: return null,
                speakText = payload["speakText"].stringOrNull() ?: "",
                asrText = asrText,
                actionId = payload["actionId"].stringOrNull() ?: "",
                actionExpiresAtMs = payload["actionExpiresAtMs"].numberOrNull()?.toLong() ?: 0,
            )
            else -> null
        }
    }

    fun streamStart(
        payload: JsonObject,
        chunks: ReceiveChannel<ByteArray>,
        completion: Deferred<AudioStreamEnd>,
    ): StreamingAudioReply? {
        val rate = payload["sampleRate"].numberOrNull()?.toInt() ?: return null
        val channels = payload["channels"].numberOrNull()?.toInt() ?: return null
        if (rate <= 0 || channels <= 0) return null
        return StreamingAudioReply(
            payload["mime"].stringOrNull() ?: return null,
            rate,
            channels,
            payload["encoding"].stringOrNull() ?: return null,
            chunks,
            completion,
        )
    }

    fun streamEnd(payload: JsonObject) = AudioStreamEnd(
        speakText = payload["speakText"].stringOrNull() ?: "",
        intent = intent(payload["intent"]),
        asrText = payload["asrText"].stringOrNull() ?: "",
    )

    private fun intent(element: JsonElement?): Intent? {
        if (element == null || !element.isJsonObject) return null
        val value = element.asJsonObject
        val sourceElement = value["source"]
        val rawSemanticElement = value["rawSemantic"]
        return Intent(
            schemaVersion = value["schemaVersion"].stringOrNull() ?: return null,
            domain = value["domain"].stringOrNull() ?: return null,
            intent = value["intent"].stringOrNull() ?: return null,
            slots = slots(value["slots"]) ?: return null,
            confidence = value["confidence"].numberOrNull() ?: return null,
            source = if (sourceElement == null) Intent.SOURCE_UNSPECIFIED
                else sourceElement.stringOrNull() ?: return null,
            rawSemantic = if (rawSemanticElement == null) null
                else rawSemanticElement.stringOrNull() ?: return null,
        )
    }

    private fun slots(element: JsonElement?): Map<String, SlotValue>? {
        if (element == null || !element.isJsonObject) return null
        return buildMap {
            for ((name, item) in element.asJsonObject.entrySet()) {
                if (!item.isJsonObject) return null
                val slot = item.asJsonObject
                val unitElement = slot["unit"]
                val unit = if (unitElement == null) null else unitElement.stringOrNull() ?: return null
                val raw = slot["value"] ?: return null
                put(name, when (slot["type"].stringOrNull()) {
                    "number" -> raw.numberOrNull()?.let { SlotValue.Number(it, unit) } ?: return null
                    "enum" -> raw.stringOrNull()?.let { SlotValue.EnumValue(it, unit) } ?: return null
                    "string" -> raw.stringOrNull()?.let { SlotValue.StringValue(it, unit) } ?: return null
                    "boolean" -> raw.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
                        ?.asBoolean?.let { SlotValue.Bool(it, unit) } ?: return null
                    else -> return null
                })
            }
        }
    }

    private fun decode(value: String): ByteArray? = try {
        Base64.getDecoder().decode(value)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun JsonElement?.stringOrNull(): String? =
        this?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonElement?.numberOrNull(): Double? =
        this?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble
}
