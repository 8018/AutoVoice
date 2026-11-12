package com.autovoice.adapterfirered

import com.k2fsa.sherpa.onnx.*
import com.autovoice.voicecore.health.*

/** Small native ownership boundary, also exercised with controlled handles in JVM tests. */
internal interface FireRedStream : AutoCloseable { fun accept(samples: FloatArray) }
internal interface FireRedRecognizer : AutoCloseable {
    fun stream(): FireRedStream
    fun decode(stream: FireRedStream): String
}
internal class SherpaDecoder(private val recognizer: FireRedRecognizer) : FireRedDecoder {
    override fun decode(samples: FloatArray): String = recognizer.stream().use { stream ->
        stream.accept(samples);recognizer.decode(stream)
    }
    override fun close() = recognizer.close()
    companion object {
        fun bootstrap() {
            if (VersionInfo.version != "2.0.1" || VersionInfo.onnxruntimeVersion != "1.28.2") throw ModelResourceException(ComponentError(ErrorCode.NATIVE_SYMBOL_MISMATCH))
        }
        fun create(model: ValidatedFireRedModel): SherpaDecoder {
            val recognizer = OfflineRecognizer(config = OfflineRecognizerConfig(modelConfig = OfflineModelConfig(
                fireRedAsrCtc = OfflineFireRedAsrCtcModelConfig(model = model.modelFile.absolutePath),
                tokens = model.tokensFile.absolutePath,numThreads = 1,provider = "cpu"),decodingMethod = "greedy_search"))
            class Stream(val value: OfflineStream) : FireRedStream {
                override fun accept(samples: FloatArray) = value.acceptWaveform(samples,sampleRate = 16000)
                override fun close() = value.release()
            }
            return SherpaDecoder(object : FireRedRecognizer {
                override fun stream(): FireRedStream = Stream(recognizer.createStream())
                override fun decode(stream: FireRedStream): String { val s = (stream as Stream).value;recognizer.decode(s);return recognizer.getResult(s).text }
                override fun close() = recognizer.release()
            })
        }
    }
}
