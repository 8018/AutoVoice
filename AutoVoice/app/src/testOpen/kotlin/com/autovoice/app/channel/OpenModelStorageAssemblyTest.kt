package com.autovoice.app.channel

import android.content.ContextWrapper
import com.autovoice.app.AudioPlayer
import com.autovoice.app.MockVehicleState
import com.autovoice.app.VehicleContext
import com.autovoice.app.VehicleContextProvider
import com.autovoice.app.VoiceEngineFactory
import com.autovoice.app.health.CapabilityEvaluator
import com.autovoice.app.health.ChannelComponentReporting
import com.autovoice.app.health.ComponentStatusRegistry
import com.autovoice.voicecore.DemoConfig
import com.autovoice.voicecore.arbiter.DecisionSink
import com.autovoice.voicecore.health.CapabilityAvailability
import com.autovoice.voicecore.health.ComponentUpdate
import com.autovoice.voicecore.health.ErrorCode
import com.autovoice.voicecore.health.Lifecycle
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class OpenModelStorageAssemblyTest {
    @TempDir lateinit var files: File

    @Test fun `unavailable model storage leaves the Open engine and independent routes assembled`() {
        for ((failure, resolveRoot) in listOf<Pair<ErrorCode, () -> File?>>(
            ErrorCode.MODEL_MISSING to { null },
            ErrorCode.RESOURCE_MISSING to { throw SecurityException("controlled storage denial") },
        )) {
            val context = object : ContextWrapper(null) {
                override fun getFilesDir() = files
                override fun getExternalFilesDir(type: String?): File? = resolveRoot()
            }
            val base = DemoConfig.fromJson(File("src/open/assets/demo-offline.json").readText())
            val config = base.copy(cloud = base.cloud.copy(enabled = true, gatewayUrl = "ws://localhost:1/ws"))
            val channel = SelectedSpeechChannel.create()
            val registry = ComponentStatusRegistry()
            val reporting = ChannelComponentReporting(registry, channel.id, registry.beginGeneration(channel.id))
            reporting.registerDefaults()
            channel.attachReporting(reporting)
            for (id in listOf("config", "frontend")) {
                val reporter = reporting.reporter(id)
                reporter.report(reporter.start("fixture"), ComponentUpdate.ready())
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val engine = VoiceEngineFactory.create(config, context, channel, reporting,
                    networkAvailable = { false }, sink = DecisionSink {}, player = AudioPlayer {},
                    vehicle = MockVehicleState(), scope = scope,
                    vehicleContext = VehicleContextProvider { VehicleContext() })
                try {
                    val snapshot = registry.snapshot.value
                    val asr = snapshot.components.getValue("local.asr")
                    assertEquals(Lifecycle.FAILED, asr.lifecycle)
                    assertEquals(failure, asr.error?.code)
                    assertEquals("resource_validate", asr.stage)
                    for (id in listOf("config", "frontend", "local.nlu")) {
                        assertEquals(Lifecycle.READY, snapshot.components.getValue(id).lifecycle, id)
                    }
                    assertEquals(Lifecycle.NOT_STARTED, snapshot.components.getValue("cloud").lifecycle)
                    assertTrue(scope.isActive)
                    val capabilities = CapabilityEvaluator.evaluate(snapshot, online = true,
                        localBridgeAvailable = channel.capabilities.localBridgeAvailable)
                    assertTrue(capabilities.canSubmitText)
                    assertEquals(CapabilityAvailability.UNAVAILABLE, capabilities.features["local"])
                } finally {
                    engine.close()
                }
            } finally {
                scope.cancel()
            }
        }
    }
}
