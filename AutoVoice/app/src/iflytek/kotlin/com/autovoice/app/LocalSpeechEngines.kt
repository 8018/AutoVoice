package com.autovoice.app

import com.autovoice.adapteriflytek.FakeCommandAsrProvider
import com.autovoice.adapteriflytek.IflytekOfflineCommandAsrStage
import com.autovoice.voicecore.DemoConfig

/** Production failures remain failures; the deterministic fake is an explicit demo provider only. */
internal fun recognizeLocalCommand(
    configuredAsr: String,
    segment: ByteArray,
    offline: () -> String?,
): String? = when (configuredAsr) {
    DemoConfig.LOCAL_ASR_IFLYTEK -> try {
        offline()
    } catch (error: IllegalStateException) {
        if (error.message?.contains(IflytekOfflineCommandAsrStage.NOT_CONFIGURED_MSG) == true) null else throw error
    }
    DemoConfig.LOCAL_ASR_FAKE -> FakeCommandAsrProvider.recognize(segment)
    else -> throw IllegalArgumentException("unsupported local.asr '$configuredAsr'")
}
