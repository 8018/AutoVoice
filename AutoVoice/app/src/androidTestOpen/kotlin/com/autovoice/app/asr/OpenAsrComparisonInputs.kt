package com.autovoice.app.asr

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

internal const val ASR_SAMPLE_RATE = 16_000

internal data class ComparisonModel(
    val id: String,
    val modelType: String,
    val files: Map<String, VerifiedFile>,
)

internal data class ComparisonFixture(
    val id: String,
    val audio: VerifiedFile,
)

internal data class VerifiedFile(val file: File, val bytes: Long, val sha256: String)

internal fun JSONObject.requireV1() {
    require(get("schemaVersion") == 1) { "Expected schemaVersion 1" }
}

internal fun comparisonModels(root: File, manifest: JSONObject): List<ComparisonModel> {
    manifest.requireV1()
    val models = manifest.getJSONArray("models")
    require(models.length() == 2) { "Expected exactly two models" }
    val modelsById = (0 until models.length()).associate { index ->
        val model = models.getJSONObject(index)
        model.getString("id") to model
    }
    require(modelsById.keys == setOf("zipformer", "sensevoice")) {
        "Expected one Zipformer and one SenseVoice"
    }
    val zipformer = modelsById.getValue("zipformer")
    val modelType = zipformer.getString("modelType")
    require(modelType in setOf("zipformer", "zipformer2")) { "Unsupported Zipformer modelType" }
    val senseVoice = modelsById.getValue("sensevoice")
    return listOf(
        readModel(root, zipformer, modelType, setOf("encoder", "decoder", "joiner", "tokens")),
        readModel(root, senseVoice, "sensevoice", setOf("model", "tokens")),
    )
}

private fun readModel(
    root: File,
    entry: JSONObject,
    modelType: String,
    roles: Set<String>,
): ComparisonModel {
    val id = entry.getString("id")
    val files = entry.getJSONObject("files")
    // Other roles can carry provenance artifacts. Verify their pins too.
    val allRoles = files.keys().asSequence().toSet()
    require(allRoles.containsAll(roles)) { "Missing required model files for $id" }
    return ComparisonModel(
        id,
        modelType,
        allRoles.associateWith { role -> verifyFile(root, files.getJSONObject(role), "$id/$role") },
    )
}

internal fun comparisonFixtures(root: File, suite: JSONObject): List<ComparisonFixture> {
    suite.requireV1()
    require(suite.getString("suiteVersion").isNotBlank()) { "Empty suiteVersion" }
    val cases = suite.getJSONArray("cases")
    require(cases.length() > 0) { "Empty fixture suite" }
    val seenIds = mutableSetOf<String>()
    return (0 until cases.length()).map { index ->
        val fixture = cases.getJSONObject(index)
        val id = fixture.getString("id")
        require(id.matches(Regex("[A-Za-z0-9_.-]+")) && seenIds.add(id)) {
            "Invalid or duplicate fixture ID at index $index"
        }
        val audio = verifyFile(root, fixture.getJSONObject("audio"), "fixture $id")
        // Preflight every WAV before creating either native recognizer.
        readVerifiedPcm(audio)
        ComparisonFixture(id, audio)
    }
}

internal fun readVerifiedPcm(audio: VerifiedFile): FloatArray {
    val wav = audio.file.readBytes()
    require(wav.size.toLong() == audio.bytes && sha256(wav) == audio.sha256) {
        "Fixture changed after verification"
    }
    return readPcm16Wave(wav)
}

internal fun comparisonVadModel(root: File, manifest: JSONObject): VerifiedFile {
    manifest.requireV1()
    return verifyFile(root, manifest.getJSONObject("asset"), "Silero VAD")
}

private fun verifyFile(root: File, entry: JSONObject, label: String): VerifiedFile {
    val file = comparisonFile(root, entry.getString("path"))
    val number = entry.get("bytes")
    require(number is Int || number is Long) { "Invalid byte count for $label" }
    val bytes = (number as Number).toLong()
    require(bytes > 0 && file.isFile && file.length() == bytes) {
        "Missing resource or byte count mismatch for $label"
    }
    val hash = entry.getString("sha256")
    require(hash.matches(Regex("[0-9a-f]{64}"))) { "Invalid SHA-256 for $label" }
    require(file.inputStream().buffered().use { sha256(it) } == hash) {
        "SHA-256 mismatch for $label"
    }
    return VerifiedFile(file, bytes, hash)
}

internal fun comparisonFile(root: File, path: String): File {
    require(path.split('/').all { it.isNotEmpty() && it != "." && it != ".." } &&
        '\\' !in path && '\u0000' !in path
    ) { "Expected a relative comparison file path" }
    val candidate = File(root, path)
    require(candidate.canonicalFile == candidate && candidate.path.startsWith(root.path + File.separator)) {
        "Comparison file is outside its fixed path"
    }
    return candidate
}

internal fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

internal fun sha256(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) return digest.digest().toHex()
        digest.update(buffer, 0, count)
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

internal fun readPcm16Wave(bytes: ByteArray): FloatArray {
    val wave = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    fun fourCc(at: Int) = String(bytes, at, 4, Charsets.US_ASCII)
    fun unsignedInt(at: Int) = wave.getInt(at).toLong() and 0xffff_ffffL
    fun unsignedShort(at: Int) = wave.getShort(at).toInt() and 0xffff

    require(bytes.size >= 12 && fourCc(0) == "RIFF" && fourCc(8) == "WAVE") {
        "Expected a RIFF/WAVE fixture"
    }
    require(unsignedInt(4) == bytes.size.toLong() - 8) { "Invalid RIFF size" }
    var offset = 12
    var fmtSeen = false
    var pcmStart = 0
    var pcmBytes = 0
    while (offset < bytes.size) {
        require(bytes.size - offset >= 8) { "Truncated WAV chunk header" }
        val length = unsignedInt(offset + 4)
        val start = offset + 8
        val paddedEnd = start + length + (length and 1L)
        require(paddedEnd <= bytes.size) { "Truncated WAV chunk" }
        when (fourCc(offset)) {
            "fmt " -> {
                require(!fmtSeen && length >= 16) { "Invalid WAV format chunk" }
                require(unsignedShort(start) == 1 && unsignedShort(start + 2) == 1 &&
                    unsignedInt(start + 4) == ASR_SAMPLE_RATE.toLong() &&
                    unsignedInt(start + 8) == ASR_SAMPLE_RATE * 2L &&
                    unsignedShort(start + 12) == 2 && unsignedShort(start + 14) == 16
                ) { "Expected mono 16 kHz PCM16 WAV" }
                fmtSeen = true
            }
            "data" -> {
                require(fmtSeen && pcmBytes == 0 && length > 0 && length % 2 == 0L) {
                    "Invalid WAV PCM data chunk"
                }
                pcmStart = start
                pcmBytes = length.toInt()
            }
        }
        offset = paddedEnd.toInt()
    }
    require(pcmBytes > 0) { "Missing WAV PCM data" }
    return FloatArray(pcmBytes / 2) { index -> wave.getShort(pcmStart + index * 2) / 32768f }
}
