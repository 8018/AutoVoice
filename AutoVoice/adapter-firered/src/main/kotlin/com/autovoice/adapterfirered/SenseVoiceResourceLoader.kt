package com.autovoice.adapterfirered

import com.autovoice.voicecore.health.ComponentError
import com.autovoice.voicecore.health.ErrorCode
import com.autovoice.voicecore.health.rethrowCancellationOrFatal
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.nio.file.Files

internal data class ValidatedSenseVoiceModel(
    val modelFile: File,
    val tokensFile: File,
    val vadFile: File,
    val metadata: Map<String, String>,
)

internal class SenseVoiceResourceLoader(
    private val root: File,
    private val expected: () -> JsonObject = SenseVoiceModelCatalog::read,
) {
    fun load(): ValidatedSenseVoiceModel {
        try {
            val catalog = expected()
            require(catalog.get("schemaVersion").asInt == 1)
            val files = catalog.getAsJsonObject("files")
            val directory = root.canonicalFile.toPath()
            fun verify(role: String, missing: ErrorCode, corrupt: ErrorCode): File {
                val pin = files.getAsJsonObject(role)
                val relativePath = pin.get("path").asString
                require(relativePath.matches(Regex("models/(sensevoice|vad)/[A-Za-z0-9_.-]+")))
                val path = directory.resolve(relativePath).normalize()
                val canonical = path.toFile().canonicalFile
                if (canonical.toPath() != path || Files.isSymbolicLink(path) || !canonical.isFile) {
                    throw ModelResourceException(ComponentError(missing))
                }
                if (canonical.length() != pin.get("bytes").asLong || sha256(canonical) != pin.get("sha256").asString) {
                    throw ModelResourceException(ComponentError(corrupt))
                }
                return canonical
            }

            val model = verify("model", ErrorCode.MODEL_MISSING, ErrorCode.MODEL_HASH_MISMATCH)
            val tokens = verify("tokens", ErrorCode.TOKENS_MISMATCH, ErrorCode.TOKENS_MISMATCH)
            val vad = verify("vad", ErrorCode.RESOURCE_MISSING, ErrorCode.MODEL_HASH_MISMATCH)
            verify("modelLicense", ErrorCode.RESOURCE_MISSING, ErrorCode.RESOURCE_MISSING)
            verify("vadLicense", ErrorCode.RESOURCE_MISSING, ErrorCode.RESOURCE_MISSING)
            return ValidatedSenseVoiceModel(model, tokens, vad, mapOf(
                "provider" to catalog.get("provider").asString,
                "modelId" to catalog.get("modelId").asString,
                "sourceRevision" to catalog.get("sourceRevision").asString,
                "vadSourceRevision" to catalog.get("vadSourceRevision").asString,
                "modelSha256" to files.getAsJsonObject("model").get("sha256").asString,
                "tokensSha256" to files.getAsJsonObject("tokens").get("sha256").asString,
                "vadSha256" to files.getAsJsonObject("vad").get("sha256").asString,
                "runtime" to catalog.get("runtimeVersion").asString,
                "ort" to catalog.get("ortVersion").asString,
            ))
        } catch (error: Throwable) {
            error.rethrowCancellationOrFatal()
            if (error is ModelResourceException) throw error
            throw ModelResourceException(ComponentError(ErrorCode.MODEL_HASH_MISMATCH))
        }
    }
}

object SenseVoiceModelCatalog {
    const val RESOURCE = "/sensevoice-model-catalog.json"

    fun read(): JsonObject = requireNotNull(SenseVoiceModelCatalog::class.java.getResourceAsStream(RESOURCE))
        .bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
}
