package com.autovoice.app.channel

import com.autovoice.voicecore.*
import com.autovoice.voicecore.arbiter.*
import com.autovoice.voicecore.session.*
import com.autovoice.voiceengine.request.CandidateCoordinator
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class OpenRequestAdmissionBindingTest {
    @Test fun `production native provider reads actual accepted owner and cannot revive across close or rebind`() = runBlocking {
        val cfg = DemoConfig.fromJson(File("src/open/assets/demo-offline.json").readText())
        val release = CompletableDeferred<Unit>()
        val c = CandidateCoordinator(cfg,OnDeviceRaceArbiter(cloudWaitMs = 1,sink = DecisionSink {}),
            LocalChainRunner { release.await();Intent.unknown("vehicle") },CloudRunner { TextReply("unused") })
        val binding = OpenRequestAdmissionBinding();val permit = AtomicBoolean(true)
        try {
            assertNull(binding.nativeProvider.capture("one"))
            binding.bind(LocalRequestAdmissionProvider(c::localAdmission))
            c.beginCapture("one") { permit.get() }
            assertNull(binding.nativeProvider.capture("one")) // Begin/VAD/caller id are not acceptance.
            assertTrue(c.submitTurn("one",ByteArray(2)) {
                val pending = requireNotNull(binding.nativeProvider.capture("one"))
                assertFalse(pending.finalized());assertTrue(pending.originalPermitValid())
                permit.set(false);assertFalse(pending.originalPermitValid())
            })
            val final = requireNotNull(binding.nativeProvider.capture("one"))
            assertTrue(final.finalized());assertFalse(final.originalPermitValid())
            assertNull(binding.nativeProvider.capture("old-or-missing"))
            binding.close();assertFalse(final.finalized());assertNull(binding.nativeProvider.capture("one"))
            binding.bind(LocalRequestAdmissionProvider { null });assertFalse(final.finalized())
            c.close()
        } finally { binding.close();release.complete(Unit);c.close() }
    }
}
