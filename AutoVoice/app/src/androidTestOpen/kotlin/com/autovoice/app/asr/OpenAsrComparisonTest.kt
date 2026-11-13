package com.autovoice.app.asr

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.VersionInfo
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

private const val CPU_THREADS = 2
private const val CHUNK_SAMPLES = ASR_SAMPLE_RATE / 10
private const val DEFAULT_ZIPFORMER_FLUSH_MS = 500
private const val MILLIS_PER_NANO = 1e-6

/** Uses only provisioned public synthetic audio and does not invoke application voice workflows. */
@RunWith(AndroidJUnit4::class)
class OpenAsrComparisonTest {
    @Test
    fun prepareComparisonDirectory() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = comparisonRoot(instrumentation.targetContext)
        instrumentation.sendStatus(0, Bundle().apply { putString("comparisonDirectory", root.path) })
    }

    @Test
    fun comparePublicFixtures() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val root = comparisonRoot(context)
        val output = AtomicFile(comparisonFile(root, "comparison-results.json"))
        val modelReports = JSONArray()
        val report = JSONObject()
            .put("schemaVersion", 1)
            .put("status", "running")
            .put("startedAtEpochMs", System.currentTimeMillis())
            .put("models", modelReports)
            .put("device", JSONObject()
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL)
                .put("sdk", Build.VERSION.SDK_INT)
                .put("supportedAbis", JSONArray(Build.SUPPORTED_ABIS.toList())))
        var stage = "manifest-validation"
        try {
            checkpoint(output, report, stage)
            val modelBytes = comparisonFile(root, "models.json").readBytes()
            val suiteBytes = comparisonFile(root, "suite.json").readBytes()
            report.put("modelsSha256", sha256(modelBytes)).put("suiteSha256", sha256(suiteBytes))
            val models = comparisonModels(root, JSONObject(modelBytes.toString(Charsets.UTF_8)))
            val suite = JSONObject(suiteBytes.toString(Charsets.UTF_8))
            val fixtures = comparisonFixtures(root, suite)
            report.put("suiteVersion", suite.getString("suiteVersion"))
            stage = "instrumentation-arguments"
            val arguments = InstrumentationRegistry.getArguments()
            val zipformerFlushMs = parseZipformerFlushMs(arguments.getString("zipformerFlushMs"))
            val pipeline = parseAsrPipeline(arguments.getString("asrPipeline"))
            val vadModel = if (pipeline == AsrPipeline.SILERO) {
                stage = "vad-manifest-validation"
                val manifest = comparisonFile(root, "vad.json").readBytes()
                val asset = comparisonVadModel(root, JSONObject(manifest.toString(Charsets.UTF_8)))
                asset to sha256(manifest)
            } else null
            stage = "runtime-identification"
            checkpoint(output, report, stage)
            val runtime = runtimeMetadata(context, instrumentation.context, zipformerFlushMs)
            report.put("runtime", runtime.put("pipeline", pipeline.id))
            runtime.put("sherpaVersion", VersionInfo.version)
                .put("onnxruntimeVersion", VersionInfo.onnxruntimeVersion)
            val admission = vadModel?.let { (asset, manifestHash) ->
                val metadata = JSONObject()
                    .put("fileSha256", asset.sha256)
                    .put("manifestSha256", manifestHash)
                    .put("threshold", SILERO_THRESHOLD)
                    .put("minSpeechMs", SILERO_MIN_SPEECH_MS)
                    .put("minSilenceMs", SILERO_MIN_SILENCE_MS)
                    .put("frameSamples", SILERO_FRAME_SAMPLES)
                    .put("contextSamples", SILERO_CONTEXT_SAMPLES)
                    .put("scope", "Unpaced full-file speech admission before ASR. Admitted audio is decoded unchanged. Suppressed fixtures never invoke ASR.")
                runtime.put("vad", metadata)
                runtime.put("decodeScope", "File VAD admission plus ASR stream processing for admitted fixtures. Excludes file reads and model loads.")
                stage = "vad-load"
                checkpoint(output, report, stage)
                val loadStart = SystemClock.elapsedRealtimeNanos()
                ComparisonSileroAdmission(asset).also { metadata.put("loadMs", elapsedMs(loadStart)) }
            }
            admission.use {
                for (model in models) {
                    val results = JSONArray()
                    val modelReport = JSONObject()
                        .put("id", model.id)
                        .put("modelType", model.modelType)
                        .put("modelBytes", model.files.values.sumOf { it.bytes })
                        .put("processPssBeforeLoadKiB", Debug.getPss())
                        .put("results", results)
                    modelReports.put(modelReport)
                    stage = "${model.id}/load"
                    checkpoint(output, report, stage)
                    val loadStart = SystemClock.elapsedRealtimeNanos()
                    val recognizer = if (model.id == "zipformer") ZipformerDecoder(model, zipformerFlushMs)
                    else SenseVoiceDecoder(model)
                    recognizer.use {
                        modelReport
                            .put("loadMs", elapsedMs(loadStart))
                            .put("processPssAfterLoadKiB", Debug.getPss())
                        for (fixture in fixtures) {
                            stage = "${model.id}/${fixture.id}"
                            checkpoint(output, report, stage)
                            val pcm = readVerifiedPcm(fixture.audio)
                            val durationMs = audioMs(pcm.size)
                            val decodeStart = SystemClock.elapsedRealtimeNanos()
                            val result = decodeFixture(recognizer, admission, pcm)
                            val decodeMs = elapsedMs(decodeStart)
                            result
                                .put("id", fixture.id)
                                .put("audioSha256", fixture.audio.sha256)
                                .put("audioMs", durationMs)
                                .put("decodeMs", decodeMs)
                                .put("rtf", decodeMs / durationMs)
                            results.put(result)
                        }
                        stage = "${model.id}/release"
                        checkpoint(output, report, stage)
                        modelReport.put("processPssAfterDecodeKiB", Debug.getPss())
                    }
                }
                if (admission != null) {
                    stage = "vad-release"
                    checkpoint(output, report, stage)
                }
            }
            report.put("status", "complete")
            checkpoint(output, report, "complete")
        } catch (failure: Throwable) {
            report.put("status", "error").put("error", JSONObject()
                .put("stage", stage)
                .put("type", failure.javaClass.name)
                .put("message", failure.message ?: ""))
            try {
                checkpoint(output, report, stage)
            } catch (writeFailure: Throwable) {
                failure.addSuppressed(writeFailure)
            }
            throw failure
        }
    }
}

private fun decodeFixture(
    recognizer: ComparisonDecoder,
    admission: ComparisonSileroAdmission?,
    pcm: FloatArray,
): JSONObject {
    if (admission == null) return recognizer.decode(pcm)

    val vadStart = SystemClock.elapsedRealtimeNanos()
    val admitted = admission.admits(pcm)
    val vadMs = elapsedMs(vadStart)
    val asrStart = SystemClock.elapsedRealtimeNanos()
    val result = if (admitted) recognizer.decode(pcm) else JSONObject().put("transcript", "")
    val asrMs = if (admitted) elapsedMs(asrStart) else 0.0
    return result.put("speechAdmitted", admitted)
        .put("asrSkipped", !admitted)
        .put("vadComputeMs", vadMs)
        .put("asrComputeMs", asrMs)
}

private fun comparisonRoot(context: Context): File {
    val external = checkNotNull(context.getExternalFilesDir(null)) {
        "App external files directory is unavailable"
    }.canonicalFile
    val root = comparisonFile(external, "asr-comparison")
    check(root.isDirectory || root.mkdir()) { "Cannot create comparison directory" }
    return root
}

private fun checkpoint(output: AtomicFile, report: JSONObject, stage: String) {
    report.put("stage", stage).put("updatedAtEpochMs", System.currentTimeMillis())
    val stream = output.startWrite()
    try {
        stream.write(report.toString(2).toByteArray(Charsets.UTF_8))
        output.finishWrite(stream)
    } catch (failure: Throwable) {
        output.failWrite(stream)
        throw failure
    }
}

internal fun parseZipformerFlushMs(argument: String?): Int {
    if (argument == null) return DEFAULT_ZIPFORMER_FLUSH_MS
    require(argument in setOf("500", "1000", "1500")) {
        "zipformerFlushMs must be 500, 1000, or 1500"
    }
    return argument.toInt()
}

private fun runtimeMetadata(
    targetContext: Context,
    instrumentationContext: Context,
    zipformerFlushMs: Int,
): JSONObject {
    val info = targetContext.applicationInfo
    val instrumentationInfo = instrumentationContext.applicationInfo
    check(info.splitSourceDirs.isNullOrEmpty() && instrumentationInfo.splitSourceDirs.isNullOrEmpty()) {
        "Comparison requires standalone target and instrumentation APKs"
    }
    val libraries = JSONObject()
    for (name in listOf("libsherpa-onnx-jni.so", "libonnxruntime.so")) {
        val extracted = File(info.nativeLibraryDir, name)
        val hash = if (extracted.isFile) {
            extracted.inputStream().buffered().use { sha256(it) }
        } else {
            // Modern packaging may map the native library straight out of the APK.
            ZipFile(info.sourceDir).use { apk ->
                val entry = Build.SUPPORTED_ABIS.firstNotNullOfOrNull { abi ->
                    apk.getEntry("lib/$abi/$name")
                }
                checkNotNull(entry) { "Native runtime library absent from target APK: $name" }
                apk.getInputStream(entry).buffered().use { sha256(it) }
            }
        }
        libraries.put(name, hash)
    }
    return JSONObject()
        .put("engine", "sherpa-onnx Android JNI")
        .put("targetApkSha256", apkHash(info.sourceDir))
        .put("instrumentationApkSha256", apkHash(instrumentationInfo.sourceDir))
        .put("librarySha256", libraries)
        .put("provider", "cpu")
        .put("threads", CPU_THREADS)
        .put("decodingMethod", "greedy_search")
        .put("sampleRate", ASR_SAMPLE_RATE)
        .put("zipformerEndpointEnabled", false)
        .put("zipformerChunkMs", audioMs(CHUNK_SAMPLES))
        .put("zipformerFlushSilenceMs", zipformerFlushMs)
        .put("sensevoiceLanguage", "zh")
        .put("sensevoiceInverseTextNormalization", true)
        .put("decodeScope", "Stream creation through final result and stream release. Includes feed, decoder, and flush work. Excludes file reads and model load.")
        .put("loadScope", "Native recognizer construction after JNI runtime identification.")
        .put("streamingScope", "Unpaced simulation from file chunks. Audio timestamps count fixture audio supplied, and compute timestamps measure elapsed processing time. Both first nonempty fields are omitted when no nonblank partial is observed before flush, including when the first text appears during flush.")
        .put("memoryScope", "Snapshots of the entire target process PSS, in KiB. Includes instrumentation and both shared and private allocations. These are not isolated model memory or peak measurements.")
}

private fun apkHash(sourceDir: String): String =
    File(sourceDir).inputStream().buffered().use { sha256(it) }

private interface ComparisonDecoder : AutoCloseable {
    fun decode(pcm: FloatArray): JSONObject
}

private class ZipformerDecoder(model: ComparisonModel, private val flushMs: Int) : ComparisonDecoder {
    private val recognizer = OnlineRecognizer(
        config = OnlineRecognizerConfig(
            enableEndpoint = false,
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = model.path("encoder"),
                    decoder = model.path("decoder"),
                    joiner = model.path("joiner"),
                ),
                tokens = model.path("tokens"),
                numThreads = CPU_THREADS,
                modelType = model.modelType,
            ),
        ),
    )

    override fun decode(pcm: FloatArray): JSONObject {
        val result = JSONObject()
        val start = SystemClock.elapsedRealtimeNanos()
        recognizer.createStream().use { stream ->
            var offset = 0
            while (offset < pcm.size) {
                val end = minOf(offset + CHUNK_SAMPLES, pcm.size)
                stream.acceptWaveform(pcm.copyOfRange(offset, end), ASR_SAMPLE_RATE)
                offset = end
                var decoded = false
                while (recognizer.isReady(stream)) {
                    recognizer.decode(stream)
                    decoded = true
                }
                if (decoded && !result.has("firstNonEmptyComputeMs") &&
                    recognizer.getResult(stream).text.isNotBlank()
                ) {
                    result.put("firstNonEmptyComputeMs", elapsedMs(start))
                    result.put("firstNonEmptyAudioFedMs", audioMs(offset))
                }
            }
            val flushStart = SystemClock.elapsedRealtimeNanos()
            // Leave room for right context so short commands and the final syllable get decoded.
            stream.acceptWaveform(FloatArray(ASR_SAMPLE_RATE * flushMs / 1_000), ASR_SAMPLE_RATE)
            stream.inputFinished()
            while (recognizer.isReady(stream)) recognizer.decode(stream)
            result
                .put("transcript", recognizer.getResult(stream).text)
                .put("flushComputeMs", elapsedMs(flushStart))
                .put("flushSilenceMs", flushMs)
        }
        return result
    }

    override fun close() = recognizer.release()
}

private class SenseVoiceDecoder(model: ComparisonModel) : ComparisonDecoder {
    private val recognizer = OfflineRecognizer(
        config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = model.path("model"),
                    language = "zh",
                    useInverseTextNormalization = true,
                ),
                tokens = model.path("tokens"),
                numThreads = CPU_THREADS,
            ),
        ),
    )

    override fun decode(pcm: FloatArray): JSONObject {
        val result = JSONObject()
        recognizer.createStream().use { stream ->
            stream.acceptWaveform(pcm, ASR_SAMPLE_RATE)
            recognizer.decode(stream)
            result.put("transcript", recognizer.getResult(stream).text)
        }
        return result
    }

    override fun close() = recognizer.release()
}

private fun ComparisonModel.path(role: String) = files.getValue(role).file.absolutePath

private fun elapsedMs(start: Long) = (SystemClock.elapsedRealtimeNanos() - start) * MILLIS_PER_NANO

private fun audioMs(samples: Int) = samples * 1000.0 / ASR_SAMPLE_RATE
