package com.autovoice.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.autovoice.adapteriflytek.IflytekOfflineCommandAsrStage
import com.autovoice.adapteriflytek.IflytekWakeWordObserver
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Real licensed device only. Feeds a public fixture directly; never executes a vehicle action. */
@RunWith(AndroidJUnit4::class)
class IflytekCombinedCapabilitiesDeviceTest {
    @Test
    fun commandRecognitionWorksWhileWakeCapabilityIsArmed() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val wake = IflytekWakeWordObserver(BuildConfig.XFYUN_APPID, BuildConfig.XFYUN_API_KEY,
            BuildConfig.XFYUN_API_SECRET, onWake = {})
        val command = IflytekOfflineCommandAsrStage(BuildConfig.XFYUN_APPID, BuildConfig.XFYUN_API_KEY,
            BuildConfig.XFYUN_API_SECRET)
        try {
            wake.init(context)
            wake.arm()
            command.init(context)
            val wav = instrumentation.context.assets.open("cmd_open_ac.wav").use { it.readBytes() }
            val buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
            check(String(wav, 0, 4, Charsets.US_ASCII) == "RIFF")
            check(String(wav, 8, 4, Charsets.US_ASCII) == "WAVE")
            var offset = 12
            var pcm: ByteArray? = null
            while (offset + 8 <= wav.size) {
                val id = String(wav, offset, 4, Charsets.US_ASCII)
                val size = buffer.getInt(offset + 4)
                check(size >= 0 && size <= wav.size - offset - 8)
                if (id == "fmt ") {
                    check(size >= 16 && buffer.getShort(offset + 8).toInt() == 1)
                    check(buffer.getShort(offset + 10).toInt() == 1 && buffer.getInt(offset + 12) == 16000)
                    check(buffer.getShort(offset + 22).toInt() == 16)
                }
                if (id == "data") { pcm = wav.copyOfRange(offset + 8, offset + 8 + size); break }
                offset += 8 + size + size % 2
            }
            assertEquals("打开空调", command.recognize(checkNotNull(pcm)))
        } finally {
            command.release()
            wake.close()
        }
    }
}
