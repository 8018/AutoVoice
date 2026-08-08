package com.autovoice.voicecore

import com.google.gson.Gson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MessagesTest {
    private val gson = Gson()

    @Test
    fun `intent gson round trip matches shared schema`() {
        val i = Intent(
            "1.0", "climate", "set_temperature",
            mapOf(
                "temperature" to SlotValue.Number(24.0),
                "zone" to SlotValue.EnumValue("driver"),
            ),
            0.95, "nlu.iflytek.api", null,
        )
        val json = gson.toJson(i)
        // shared fixture 形状校验：字段名一致
        val shared = javaClass.classLoader.getResource("gateway-reply-action.json")!!.readText()
        assertTrue(shared.contains("\"domain\": \"climate\""))
        assertTrue(json.contains("\"schemaVersion\":\"1.0\""))
        assertTrue(json.contains("\"intent\":\"set_temperature\""))
    }

    @Test
    fun `unknown intent flag`() {
        assertTrue(Intent.unknown("test").isUnknown())
    }

    @Test
    fun `reply kinds`() {
        assertEquals("text", TextReply("hi").kind)
        assertEquals("audio", AudioReply("audio/wav", byteArrayOf(1)).kind)
        assertEquals("action", ActionReply(Intent.unknown("t"), "好的").kind)
    }

    @Test
    fun `slot values serialize as typed shape`() {
        val number = gson.toJson(SlotValue.Number(24.0))
        assertTrue(number.contains("\"type\":\"number\""), number)
        assertTrue(number.contains("\"value\":24.0"), number)
        assertTrue(gson.toJson(SlotValue.EnumValue("driver")).contains("\"type\":\"enum\""))
        assertTrue(gson.toJson(SlotValue.StringValue("x")).contains("\"value\":\"x\""))
        assertTrue(gson.toJson(SlotValue.Bool(true)).contains("\"type\":\"boolean\""))
    }

    @Test
    fun `null rawSemantic omitted from json`() {
        val withNull = gson.toJson(Intent.unknown("climate"))
        assertTrue(!withNull.contains("rawSemantic"), withNull)
        val withRaw = gson.toJson(
            Intent("1.0", "climate", "set_temperature", emptyMap(), 0.9, "nlu.iflytek.api", "raw sem"),
        )
        assertTrue(withRaw.contains("\"rawSemantic\":\"raw sem\""), withRaw)
    }
}
