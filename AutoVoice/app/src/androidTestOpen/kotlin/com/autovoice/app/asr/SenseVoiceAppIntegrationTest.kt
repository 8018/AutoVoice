package com.autovoice.app.asr

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.autovoice.app.channel.SelectedSpeechChannel
import com.autovoice.app.health.ChannelComponentReporting
import com.autovoice.app.health.ComponentStatusRegistry
import com.autovoice.voicecore.*
import com.autovoice.voicecore.health.Lifecycle
import java.io.File
import java.text.Normalizer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the same channel factory, model catalog, owner/admission and callbacks used by the app.
 * No microphone, cloud upload, navigation or vehicle actions. Public fixed PCM fixtures only. */
@RunWith(AndroidJUnit4::class)
class SenseVoiceAppIntegrationTest {
    @Test fun appFactoryRecognizesPublicSuiteAndRejectsExpiredOwners() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val root = File(requireNotNull(context.getExternalFilesDir(null)), "asr-comparison").canonicalFile
        val suiteBytes = File(root, "suite.json").readBytes()
        check(sha256(suiteBytes) == "b6f11ce13988b035c0b80d08dcf6628de8953e8919fff8f94ea1890b5b0a012c") { "Fixture suite differs from approved v1" }
        val suite = JSONObject(suiteBytes.toString(Charsets.UTF_8))
        val fixtures = comparisonFixtures(root, suite)
        val channel = SelectedSpeechChannel.create()
        val config = channel.validateConfig(context.assets.open("demo-offline.json").bufferedReader().use {
            DemoConfig.fromJson(it.readText())
        })
        assertEquals("sensevoice.int8", config.local.asr)
        val registry = ComponentStatusRegistry()
        val reporting = ChannelComponentReporting(registry, channel.id, registry.beginGeneration(channel.id))
        reporting.registerDefaults()
        channel.attachReporting(reporting)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val live = AtomicBoolean(true)
        val deadline = AtomicLong(Long.MAX_VALUE)
        val rows = JSONArray()
        val report = JSONObject().put("schemaVersion", 1).put("status", "running")
            .put("suiteSha256", sha256(suiteBytes)).put("provider", config.local.asr)
            .put("measurement", "Actual app channel factory and ASR lifecycle on public files; excludes microphone/AEC/network/actions")
            .put("targetApkSha256", File(context.applicationInfo.sourceDir).inputStream().use { sha256(it) })
            .put("testApkSha256", File(instrumentation.context.applicationInfo.sourceDir).inputStream().use { sha256(it) })
            .put("results", rows)
        val output = File(root, "sensevoice-app-results.json")
        fun save() = output.writeText(report.toString(2))
        save()
        try {
            channel.createLocalEngines(context, config, scope).use { assembly ->
                assembly.bindAdmission(LocalRequestAdmissionProvider {
                    LocalRequestAdmission(deadline.get(), { true }, { true }, live::get)
                })
                val start = SystemClock.elapsedRealtime()
                while (registry.snapshot.value.components.getValue("local.asr").lifecycle !in setOf(Lifecycle.READY, Lifecycle.FAILED)) {
                    check(SystemClock.elapsedRealtime() - start < 60_000) { "ASR initialization timeout" }
                    Thread.sleep(20)
                }
                val status = registry.snapshot.value.components.getValue("local.asr")
                report.put("loadMs", SystemClock.elapsedRealtime() - start).put("versions", JSONObject(status.versions))
                assertEquals(Lifecycle.READY, status.lifecycle)
                assertEquals("sensevoice.int8", status.descriptor.provider)
                assertEquals("sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17", status.versions["modelId"])
                var firstPcm: ByteArray? = null
                for ((index, fixture) in fixtures.withIndex()) {
                    val samples = readVerifiedPcm(fixture.audio)
                    val pcm = ByteArray(samples.size * 2)
                    for (i in samples.indices) {
                        val sample = (samples[i] * 32768).toInt().toShort().toInt()
                        pcm[i * 2] = sample.toByte(); pcm[i * 2 + 1] = (sample shr 8).toByte()
                    }
                    if (firstPcm == null) firstPcm = pcm
                    live.set(true); deadline.set(System.nanoTime() / 1_000_000 + 30_000)
                    val transcripts = mutableListOf<String>(); var established = 0
                    val sink = object : AsrSink {
                        override fun onTranscript(result: AsrResult) { transcripts.add(result.text) }
                        override fun onTurnEstablished() { established++ }
                    }
                    val before = SystemClock.elapsedRealtimeNanos()
                    val result = assembly.asr.recognize("public-${fixture.id}", pcm, sink)
                    val elapsedMs = (SystemClock.elapsedRealtimeNanos() - before) / 1_000_000.0
                    val expected = suite.getJSONArray("cases").getJSONObject(index).getJSONArray("expected")
                    val text = result?.text.orEmpty()
                    val exact = (0 until expected.length()).any { normalize(expected.getString(it)) == normalize(text) }
                    val callbacksValid = if (text.isEmpty()) transcripts.isEmpty() && established == 0
                        else transcripts == listOf(text) && established == 1
                    rows.put(JSONObject().put("id", fixture.id).put("transcript", text).put("exact", exact)
                        .put("callbacksValid", callbacksValid).put("elapsedMs", elapsedMs).put("audioSha256", fixture.audio.sha256))
                    save()
                }
                val pcm = requireNotNull(firstPcm)
                var expiredCallbacks = 0
                val rejectedSink = object : AsrSink {
                    override fun onTranscript(result: AsrResult) { expiredCallbacks++ }
                    override fun onTurnEstablished() { expiredCallbacks++ }
                }
                deadline.set(0)
                assertNull(assembly.asr.recognize("expired", pcm, rejectedSink))
                deadline.set(System.nanoTime() / 1_000_000 + 30_000); live.set(false)
                assertNull(assembly.asr.recognize("old-owner", pcm, rejectedSink))
                assertEquals(0, expiredCallbacks)
                live.set(true)
                var staleEstablished = 0
                var revocationTranscripts = 0
                assertNull(assembly.asr.recognize("owner-revoked-in-callback", pcm, object : AsrSink {
                    override fun onTranscript(result: AsrResult) { revocationTranscripts++; live.set(false) }
                    override fun onTurnEstablished() { staleEstablished++ }
                }))
                assertEquals(1, revocationTranscripts)
                assertFalse(live.get())
                assertEquals(0, staleEstablished)
                report.put("expiredAndRevokedOwnersRejected", true)
            }
            val passed = rows.length() == 29 && (0 until rows.length()).all {
                rows.getJSONObject(it).getBoolean("exact") && rows.getJSONObject(it).getBoolean("callbacksValid") &&
                    rows.getJSONObject(it).getDouble("elapsedMs") <= 15_000
            }
            report.put("status", "complete").put("passed", passed); save()
            assertTrue("Integrated ASR fixture or callback failure; inspect sensevoice-app-results.json", passed)
        } catch (error: Throwable) {
            report.put("status", "error").put("errorType", error.javaClass.simpleName); save(); throw error
        } finally { live.set(false); scope.cancel() }
    }
    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .replace(Regex("第([1-9])个")) { "第${"一二三四五六七八九"[it.groupValues[1].toInt() - 1]}个" }
        .replace(Regex("[\\p{P}\\p{Z}\\s]"), "").lowercase()
}
