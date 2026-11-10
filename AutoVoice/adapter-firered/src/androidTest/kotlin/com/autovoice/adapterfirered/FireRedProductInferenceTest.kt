package com.autovoice.adapterfirered

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.autovoice.voicecore.*
import com.autovoice.voicecore.health.*
import com.autovoice.voiceengine.local.TextRuleNluEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList

/** Requires explicit preinstalled fixed resources. No assume/skip, no microphone/network, no model in APK.
 * Admission is a controlled finalized test ticket; the App's real Coordinator bridge belongs to Task6. */
@RunWith(AndroidJUnit4::class)
class FireRedProductInferenceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun root() = requireNotNull(instrumentation.targetContext.getExternalFilesDir("speech-models"))
    @Test fun prepareResourceDirectory() {
        assertTrue(root().isDirectory)
        Log.i(TAG,"RESOURCE_ROOT=${root().absolutePath}")
    }
    @Test fun fixedResourcesRealFinalAndStrictNlu(): Unit = runBlocking {
        val events = CopyOnWriteArrayList<ComponentStatusEvent>()
        val reporter = ComponentReporter(ComponentDescriptor("local.asr","firered.ctc.int8","open"),1,ComponentStatusSink { events.add(it) })
        val engine = FireRedAsrEngine.create(root(),FireRedAdmissionProvider { FireRedAdmission(System.nanoTime()/1_000_000 + 120_000,{true},{false}) },reporter)
        try {
            assertTrue("Fixed external resources/native must initialize; events=$events",engine.awaitReady(120_000))
            val nlu = TextRuleNluEngine()
            val inputs = listOf(Triple("open-window.wav","打开车窗","power_on"),Triple("close-window.wav","关闭车窗","power_off"),
                Triple("exit-dialogue.wav","退出对话","exit_dialogue"),Triple("negated-open-window.wav","不要打开车窗","unknown"),
                Triple("official-1.wav",null,null))
            for ((file,expected,intent) in inputs) {
                var transcripts = 0;var established = 0
                val pcm = pcm(file)
                val start = System.nanoTime()
                val result = engine.recognize(file,pcm,object : AsrSink {
                    override fun onTranscript(result: AsrResult) { assertTrue(result.isFinal);assertTrue(result.text.isNotBlank());transcripts++ }
                    override fun onTurnEstablished() { established++ }
                })
                assertNotNull(file,result);assertTrue(result!!.isFinal)
                assertEquals(1,transcripts);assertEquals(1,established)
                if (expected != null) assertEquals(file,expected,result.text)
                val semantic = nlu.understand(file,pcm,result)
                assertNull(semantic.recognizedText)
                if (intent == "unknown") assertTrue(semantic.intent.isUnknown())
                else if (intent != null) assertEquals(intent,semantic.intent.intent)
                Log.i(TAG,"RESULT file=$file text=${result.text} intent=${semantic.intent.intent} decodeMs=${(System.nanoTime()-start)/1_000_000}")
            }
            assertEquals("one actual model initialization across all segments",1,events.count { it.update.lifecycle == Lifecycle.READY })
            assertEquals("ca3dbabd82170110cc0b343c2890866d449984bc9cd92b9a18371ff80a81bb99",events.first { it.update.lifecycle == Lifecycle.READY }.update.versions["modelSha256"])
        } finally { engine.close() }
        assertNull(engine.recognize("closed",byteArrayOf(0,0),AsrSink.NOOP))
        Log.i(TAG,"PASS production adapter lifecycle/final/NLU; controlled admission; no device acoustic certification")
    }
    private fun pcm(file: String): ByteArray {
        val bytes = instrumentation.context.assets.open(file).use { it.readBytes() }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(String(bytes,0,4) == "RIFF" && String(bytes,8,4) == "WAVE")
        var position = 12;var formatOk = false
        while (position + 8 <= bytes.size) {
            val id = String(bytes,position,4);val size = b.getInt(position+4);val start = position+8
            require(size >= 0 && start.toLong()+size <= bytes.size)
            if (id == "fmt ") { require(size >= 16);formatOk = b.getShort(start).toInt() == 1 && b.getShort(start+2).toInt() == 1 && b.getInt(start+4) == 16000 && b.getShort(start+14).toInt() == 16 }
            if (id == "data") { require(formatOk && size % 2 == 0);return bytes.copyOfRange(start,start+size) }
            position = start+size+(size and 1)
        }
        error("PCM data missing")
    }
    companion object { const val TAG = "FireRedProductProbe" }
}
