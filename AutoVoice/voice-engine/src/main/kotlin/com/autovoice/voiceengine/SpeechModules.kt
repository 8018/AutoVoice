package com.autovoice.voiceengine

import com.autovoice.voicecore.AsrEngine
import com.autovoice.voicecore.AsrResult
import com.autovoice.voicecore.AsrSink
import com.autovoice.voicecore.NluEngine
import com.autovoice.voicecore.NluResult
import com.autovoice.voicecore.Reply

/** One engine-owned ASR entry for both local and cloud routes. Binding is composition-only. */
class AsrModule {
    private data class CloudRoute(
        val engine: AsrEngine,
        val release: (String) -> Unit,
        val close: () -> Unit,
    )

    @Volatile private var local: AsrEngine? = null
    @Volatile private var cloud: CloudRoute? = null

    @Synchronized
    fun bindLocal(engine: AsrEngine) {
        check(local == null) { "local ASR already bound" }
        local = engine
    }

    @Synchronized
    fun bindCloud(engine: AsrEngine, release: (String) -> Unit, close: () -> Unit) {
        check(cloud == null) { "cloud ASR already bound" }
        cloud = CloudRoute(engine, release, close)
    }

    fun recognizeLocal(turnId: String, segment: ByteArray, sink: AsrSink): AsrResult? =
        requireNotNull(local) { "local ASR not bound" }.recognize(turnId, segment, sink)

    /** Cloud ASR observes the already shared upload; this call never uploads PCM a second time. */
    fun recognizeCloud(turnId: String, segment: ByteArray, sink: AsrSink): AsrResult? =
        requireNotNull(cloud) { "cloud ASR not bound" }.engine.recognize(turnId, segment, sink)

    fun releaseCloud(turnId: String) = requireNotNull(cloud) { "cloud ASR not bound" }.release(turnId)

    fun closeCloud() = requireNotNull(cloud) { "cloud ASR not bound" }.close()
}

/** One engine-owned NLU entry; route outputs retain their existing types until candidate arbitration. */
class NluModule {
    @Volatile private var local: NluEngine<NluResult>? = null
    @Volatile private var cloud: NluEngine<Reply>? = null

    @Synchronized
    fun bindLocal(engine: NluEngine<NluResult>) {
        check(local == null) { "local NLU already bound" }
        local = engine
    }

    @Synchronized
    fun bindCloud(engine: NluEngine<Reply>) {
        check(cloud == null) { "cloud NLU already bound" }
        cloud = engine
    }

    suspend fun understandLocal(turnId: String, segment: ByteArray, asr: AsrResult?): NluResult =
        requireNotNull(local) { "local NLU not bound" }.understand(turnId, segment, asr)

    suspend fun understandCloud(turnId: String, segment: ByteArray, asr: AsrResult?): Reply =
        requireNotNull(cloud) { "cloud NLU not bound" }.understand(turnId, segment, asr)
}
