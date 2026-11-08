package com.autovoice.adapterfirered

import com.autovoice.voicecore.health.*
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class ModelResourceException(val error: ComponentError) : IllegalStateException(error.code.message)
data class ValidatedFireRedModel(val modelFile: File,val tokensFile: File,val metadata: Map<String,String>)

/** Fixed packaged catalog is trusted input; external manifest may never choose model hashes/paths. */
class ModelResourceLoader internal constructor(private val root: File,private val expected: () -> JsonObject) {
    constructor(root: File) : this(root,{ FireRedModelCatalog.read() })
    fun load(): ValidatedFireRedModel {
        try {
            val catalog = expected()
            val modelId = catalog.get("modelId").asString
            val revision = catalog.get("exporterRevision").asString
            require(modelId.matches(Regex("[a-z0-9-]+")) && revision.matches(Regex("[0-9a-f]{40}")))
            val directory = File(root,"$modelId/$revision").canonicalFile
            if (!directory.toPath().startsWith(root.canonicalFile.toPath())) fail(ErrorCode.RESOURCE_MISSING)
            val manifest = File(directory,"manifest.json")
            if (!manifest.isFile) fail(ErrorCode.MODEL_MISSING)
            if (manifest.length() > 65536 || Files.isSymbolicLink(manifest.toPath())) fail(ErrorCode.MODEL_HASH_MISMATCH)
            val supplied = JsonParser.parseString(manifest.readText()).asJsonObject
            val expectedFiles = catalog.getAsJsonObject("files")
            if (supplied.getAsJsonObject("files")?.get("tokens.txt") != expectedFiles.get("tokens.txt")) fail(ErrorCode.TOKENS_MISMATCH)
            if (supplied != catalog) fail(ErrorCode.MODEL_HASH_MISMATCH)
            for ((name,entry) in expectedFiles.entrySet()) {
                val known = name == "model.int8.onnx" || name == "tokens.txt" || name.matches(Regex("licenses/[A-Za-z0-9_.-]+"))
                if (!known) fail(ErrorCode.RESOURCE_MISSING)
                val missing = when (name) { "model.int8.onnx" -> ErrorCode.MODEL_MISSING;"tokens.txt" -> ErrorCode.TOKENS_MISMATCH;else -> ErrorCode.RESOURCE_MISSING }
                val corrupt = when (name) { "model.int8.onnx" -> ErrorCode.MODEL_HASH_MISMATCH;"tokens.txt" -> ErrorCode.TOKENS_MISMATCH;else -> ErrorCode.RESOURCE_MISSING }
                val raw = File(directory,name);val file = raw.canonicalFile
                if (!file.toPath().startsWith(directory.toPath()) || Files.isSymbolicLink(raw.toPath()) || !file.isFile) fail(missing)
                val info = entry.asJsonObject
                if (file.length() != info.get("size").asLong || sha256(file) != info.get("sha256").asString) fail(corrupt)
            }
            val metadata = mapOf("modelId" to modelId,"modelSha256" to expectedFiles.getAsJsonObject("model.int8.onnx").get("sha256").asString,
                "tokensSha256" to expectedFiles.getAsJsonObject("tokens.txt").get("sha256").asString,"sourceRevision" to revision,
                "runtime" to catalog.get("runtimeVersion").asString,"ort" to catalog.get("ortVersion").asString)
            return ValidatedFireRedModel(File(directory,"model.int8.onnx").canonicalFile,File(directory,"tokens.txt").canonicalFile,metadata)
        } catch (error: Throwable) {
            error.rethrowCancellationOrFatal()
            if (error is ModelResourceException) throw error
            fail(ErrorCode.MODEL_HASH_MISMATCH)
        }
    }
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(1 shl 20).use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) { val n = input.read(buffer);if (n < 0) break;digest.update(buffer,0,n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun fail(code: ErrorCode): Nothing = throw ModelResourceException(ComponentError(code))
}
object FireRedModelCatalog {
    const val RESOURCE = "/firered-model-catalog.json"
    fun read(): JsonObject = requireNotNull(FireRedModelCatalog::class.java.getResourceAsStream(RESOURCE)).bufferedReader().use {
        JsonParser.parseReader(it).asJsonObject
    }
}
