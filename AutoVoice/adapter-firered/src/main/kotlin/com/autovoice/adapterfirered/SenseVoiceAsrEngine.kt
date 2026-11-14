package com.autovoice.adapterfirered

import com.autovoice.voicecore.health.ComponentReporter
import java.io.File

/** Acquires a lease on the serialized SenseVoice and Silero models. */
object SenseVoiceAsrEngine {
    private var root: File? = null
    private var shared: FireRedRuntime<ValidatedSenseVoiceModel>? = null

    @Synchronized fun create(
        modelRoot: File,
        admission: FireRedAdmissionProvider,
        reporter: ComponentReporter = ComponentReporter.none(),
    ): FireRedAsrEngine {
        val canonical = modelRoot.canonicalFile
        require(root == null || root == canonical) { "SenseVoice model root cannot change within a process" }
        if (shared == null) {
            root = canonical
            shared = FireRedRuntime(
                load = SenseVoiceResourceLoader(canonical)::load,
                create = SenseVoiceDecoder::create,
                bootstrap = SherpaDecoder::bootstrap,
                metadata = ValidatedSenseVoiceModel::metadata,
                name = "SenseVoice",
                normalizeTranscript = String::trim,
            )
        }
        return FireRedAsrEngine(requireNotNull(shared), admission, reporter)
    }
}
