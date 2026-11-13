package com.autovoice.app.asr

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class OpenAsrComparisonInputsTest {
    @Test
    fun pipelineDefaultsToRawAndRejectsUnknownModes() {
        assertEquals(AsrPipeline.RAW, parseAsrPipeline(null))
        assertEquals(AsrPipeline.RAW, parseAsrPipeline("raw"))
        assertEquals(AsrPipeline.SILERO, parseAsrPipeline("silero"))
        assertInvalid("asrPipeline") { parseAsrPipeline("other") }
    }

    @Test
    fun sileroInputCarriesContextAndPadsOnlyTheLastPartialFrame() {
        val pcm = FloatArray(513) { (it + 1) / 1024f }
        val inputs = mutableListOf<FloatArray>()
        assertTrue(evaluateSileroAdmission(pcm) {
            inputs.add(it.copyOf())
            0.9f
        })
        assertEquals(2, inputs.size)
        assertArrayEquals(FloatArray(64) + pcm.copyOfRange(0, 512), inputs[0], 0f)
        assertArrayEquals(pcm.copyOfRange(448, 513) + FloatArray(511), inputs[1], 0f)
    }

    @Test
    fun sileroRequiresTwoConsecutiveProbabilitiesAboveThreshold() {
        val pcm = FloatArray(512 * 3)
        val probabilities = listOf(0.9f, 0.5f, 0.9f).iterator()
        assertFalse(evaluateSileroAdmission(pcm) { probabilities.next() })
        assertFalse(evaluateSileroAdmission(pcm) { 0.0f })
    }

    @Test
    fun corruptVadAssetFailsPreflight() = withFixture { root, _, asset ->
        val manifest = JSONObject().put("schemaVersion", 1).put("asset", asset)
        assertEquals(asset.getString("sha256"), comparisonVadModel(root, manifest).sha256)
        asset.put("sha256", "0".repeat(64))
        assertInvalid("SHA-256 mismatch for Silero VAD") { comparisonVadModel(root, manifest) }
    }

    @Test
    fun zipformerFlushOverrideAcceptsOnlyDiagnosticDurations() {
        assertEquals(500, parseZipformerFlushMs(null))
        assertEquals(500, parseZipformerFlushMs("500"))
        assertEquals(1000, parseZipformerFlushMs("1000"))
        assertEquals(1500, parseZipformerFlushMs("1500"))
        assertInvalid("zipformerFlushMs") { parseZipformerFlushMs("750") }
        assertInvalid("zipformerFlushMs") { parseZipformerFlushMs("") }
    }

    @Test
    fun pcmConversionPreservesBothSignedExtrema() {
        assertArrayEquals(
            floatArrayOf(-1f, 0.9999695f),
            readPcm16Wave(testWav()),
            0f,
        )
    }

    @Test
    fun rejectsStereoAndTruncatedWav() {
        val wav = testWav()
        val stereo = wav.copyOf().apply { this[22] = 2 }
        assertInvalid("mono") { readPcm16Wave(stereo) }
        assertInvalid("RIFF size") { readPcm16Wave(wav.copyOf(wav.size - 1)) }
    }

    @Test
    fun duplicateFixtureIdFailsPreflight() = withFixture { root, suite, _ ->
        val cases = suite.getJSONArray("cases")
        cases.put(cases.getJSONObject(0))
        assertInvalid("duplicate fixture ID") { comparisonFixtures(root, suite) }
    }

    @Test
    fun missingAndCorruptFixturesFailPreflight() = withFixture { root, suite, _ ->
        val fixture = File(root, "fixture.wav")
        val wav = fixture.readBytes()
        wav[wav.lastIndex] = 1
        fixture.writeBytes(wav)
        assertInvalid("SHA-256 mismatch") { comparisonFixtures(root, suite) }
        assertTrue(fixture.delete())
        assertInvalid("Missing resource") { comparisonFixtures(root, suite) }
    }

    @Test
    fun escapingRelativePathFailsPreflight() = withFixture { root, suite, asset ->
        asset.put("path", "../fixture.wav")
        assertInvalid("relative comparison file path") { comparisonFixtures(root, suite) }
    }

    @Test
    fun corruptModelAssetFailsPreflight() = withFixture { root, _, asset ->
        val zipformerFiles = JSONObject()
            .put("encoder", asset).put("decoder", asset).put("joiner", asset).put("tokens", asset)
        val senseVoiceFiles = JSONObject().put("model", asset).put("tokens", asset)
        val models = JSONArray()
            .put(JSONObject().put("id", "zipformer").put("modelType", "zipformer").put("files", zipformerFiles))
            .put(JSONObject().put("id", "sensevoice").put("files", senseVoiceFiles))
        val manifest = JSONObject().put("schemaVersion", 1).put("models", models)
        asset.put("sha256", "0".repeat(64))
        assertInvalid("SHA-256 mismatch for zipformer") { comparisonModels(root, manifest) }
    }

    private fun withFixture(test: (File, JSONObject, JSONObject) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "asr-input-test-")
            .toFile().canonicalFile
        try {
            val wav = testWav()
            File(root, "fixture.wav").writeBytes(wav)
            val asset = JSONObject().put("path", "fixture.wav").put("bytes", wav.size)
                .put("sha256", sha256(wav))
            val cases = JSONArray().put(JSONObject().put("id", "fixture-1").put("audio", asset))
            val suite = JSONObject().put("schemaVersion", 1).put("suiteVersion", "1.0").put("cases", cases)
            assertEquals(1, comparisonFixtures(root, suite).size)
            test(root, suite, asset)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun assertInvalid(message: String, block: () -> Unit) {
        val failure = assertThrows(IllegalArgumentException::class.java) { block() }
        assertTrue("Unexpected validation error: ${failure.message}", failure.message.orEmpty().contains(message))
    }

    private fun testWav(): ByteArray = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN)
        .put("RIFF".toByteArray()).putInt(40).put("WAVEfmt ".toByteArray()).putInt(16)
        .putShort(1).putShort(1).putInt(16_000).putInt(32_000).putShort(2).putShort(16)
        .put("data".toByteArray()).putInt(4).putShort(Short.MIN_VALUE).putShort(Short.MAX_VALUE)
        .array()
}
