package com.autovoice.app

import com.autovoice.adapteriflytek.IflytekOfflineCommandAsrStage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class VoiceEngineFactoryTest {
    @Test fun `offline sdk unavailable does not fabricate a command`() {
        val result = recognizeLocalCommand("iflytek.offline", byteArrayOf(1)) {
            throw IllegalStateException(IflytekOfflineCommandAsrStage.NOT_CONFIGURED_MSG)
        }

        assertNull(result)
    }

    @Test fun `fake command provider requires explicit configuration`() {
        assertEquals("打开空调", recognizeLocalCommand("iflytek.fake-cmd", byteArrayOf(1)) { null })
        val error = assertThrows(IllegalArgumentException::class.java) {
            recognizeLocalCommand("unknown", byteArrayOf(1)) { "不应调用" }
        }
        assertEquals("unsupported local.asr 'unknown'", error.message)
    }
}
