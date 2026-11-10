package com.autovoice.app

import android.content.ContextWrapper
import com.autovoice.app.channel.*
import com.autovoice.app.health.*
import com.autovoice.voicecore.*
import com.autovoice.voicecore.arbiter.DecisionSink
import com.autovoice.voicecore.health.*
import com.autovoice.voiceengine.local.TextRuleNluEngine
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class VoiceEngineAdmissionBindingTest {
    @TempDir lateinit var dir: File
    @Test fun `actual offline factory binds accepted facts before local work and final output is observed once`() = runBlocking {
        val selected = SelectedSpeechChannel.create()
        val cfg = DemoConfig.fromJson(File("src/${selected.id}/assets/demo-offline.json").readText())
        val provider = AtomicReference<LocalRequestAdmissionProvider?>()
        val entered = CompletableDeferred<LocalRequestAdmission>()
        val executed = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val recognized = java.util.concurrent.CopyOnWriteArrayList<String?>()
        val bound = java.util.concurrent.atomic.AtomicBoolean(false)
        val asr = object : AsrEngine {
            override fun recognize(turnId: String,segment: ByteArray,sink: AsrSink): AsrResult? {
                val facts = requireNotNull(provider.get()?.capture(turnId));entered.complete(facts)
                check(release.await(2,TimeUnit.SECONDS))
                if (!facts.valid(System.nanoTime()/1_000_000)) return null
                return AsrResult("打开车窗",isFinal = true).also { sink.onTranscript(it);sink.onTurnEstablished() }
            }
        }
        val channel = object : SpeechChannel by selected {
            override val capabilities get() = SpeechChannelCapabilities(localBridgeAvailable = bound.get(),
                auxiliaryBridgeAvailable = bound.get(),openMicDeviceValidated = false)
            override fun createLocalEngines(context: android.content.Context,config: DemoConfig,scope: CoroutineScope) =
                LocalEngineAssembly(asr,TextRuleNluEngine(),bindAdmission = { provider.set(it);bound.set(true) }) { provider.set(null) }
        }
        val context = object : ContextWrapper(null) { override fun getFilesDir() = dir }
        val registry = ComponentStatusRegistry();val reporting = ChannelComponentReporting(registry,channel.id,registry.beginGeneration(channel.id))
        reporting.registerDefaults()
        for (id in listOf("config","iflytek.runtime","frontend","local.asr","local.nlu","vad.aux","aec")) {
            val r = reporting.reporter(id);r.report(r.start("fixture"),ComponentUpdate.ready())
        }
        val scope = CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val engine = VoiceEngineFactory.create(cfg,context,channel,reporting,networkAvailable = { false },
            sink = DecisionSink {},player = AudioPlayer {},vehicle = MockVehicleState(),scope = scope,
            onLocalRecognized = recognized::add,onVehicleApplied = { executed.complete(Unit) },
            vehicleContext = VehicleContextProvider { VehicleContext() })
        try {
            assertTrue(bound.get());assertNull(provider.get()!!.capture("unknown"))
            val caps = CapabilityEvaluator.evaluate(registry.snapshot.value,false,channel.capabilities.auxiliaryBridgeAvailable,
                channel.capabilities.localBridgeAvailable,openMicDeviceValidated = channel.capabilities.openMicDeviceValidated)
            assertEquals(CapabilityAvailability.AVAILABLE,caps.features["local"])
            assertEquals(CapabilityAvailability.AVAILABLE,caps.features["follow_up"])
            assertEquals(CapabilityAvailability.UNAVAILABLE,caps.features["barge_in"])
            engine.onWake();engine.onListeningStart();engine.onTurnSegment(ByteArray(1024))
            val facts = withTimeout(2000) { entered.await() }
            assertTrue(facts.finalized());engine.stopRecognition();assertTrue(facts.valid(System.nanoTime()/1_000_000))
            release.countDown()
            withTimeout(2000) { executed.await() }
            assertEquals(listOf("打开车窗"),recognized)
        } finally { release.countDown();engine.close() }
    }
}
