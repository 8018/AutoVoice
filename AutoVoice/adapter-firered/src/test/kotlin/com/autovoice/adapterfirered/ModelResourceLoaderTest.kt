package com.autovoice.adapterfirered

import com.google.gson.JsonParser
import com.google.gson.JsonObject
import com.autovoice.voicecore.health.ErrorCode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.io.File
import java.security.MessageDigest

internal class ModelFixture : AutoCloseable {
    val root = Files.createTempDirectory("firered-model-fixture").toFile()
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    val files = linkedMapOf("model.int8.onnx" to byteArrayOf(1,2,3),"tokens.txt" to "<blank> 0\n窗 1\n".toByteArray(),"licenses/LICENSE" to "fixture license".toByteArray())
    val catalog: JsonObject = JsonParser.parseString("""{"schemaVersion":1,"modelId":"fixture-ctc","exporterRevision":"0123456789012345678901234567890123456789","runtimeRevision":"af2d8d81dbfa61c4cea4c568da78f3982edca005","runtimeVersion":"2.0.1","ortVersion":"1.28.2","androidApi":27,"provider":"cpu","numThreads":1,"sampleRate":16000,"files":{}}""").asJsonObject.apply {
        for ((name,bytes) in files) getAsJsonObject("files").add(name,JsonObject().apply { addProperty("size",bytes.size);addProperty("sha256",sha(bytes)) })
    }
    val directory = File(root,"fixture-ctc/0123456789012345678901234567890123456789")
    init { directory.mkdirs();files.forEach { (n,b) -> File(directory,n).apply { parentFile.mkdirs();writeBytes(b) } };File(directory,"manifest.json").writeText(catalog.toString()) }
    fun loader() = ModelResourceLoader(root) { catalog.deepCopy() }
    override fun close() { root.deleteRecursively() }
}
class ModelResourceLoaderTest {
    @Test fun `fixed manifest and all file digests authorize exactly one versioned resource set`() {
        ModelFixture().use { f ->
            val verified = f.loader().load()
            assertEquals(File(f.directory,"model.int8.onnx").canonicalFile,verified.modelFile)
            assertEquals(File(f.directory,"tokens.txt").canonicalFile,verified.tokensFile)
            assertEquals("fixture-ctc",verified.metadata["modelId"])
            assertEquals(f.catalog.getAsJsonObject("files").getAsJsonObject("model.int8.onnx").get("sha256").asString,verified.metadata["modelSha256"])
        }
    }
    @Test fun `missing model corrupt model tokens mismatch and missing license are distinct failures`() {
        for ((name,expected) in listOf("model.int8.onnx" to ErrorCode.MODEL_HASH_MISMATCH,"tokens.txt" to ErrorCode.TOKENS_MISMATCH,"licenses/LICENSE" to ErrorCode.RESOURCE_MISSING)) {
            ModelFixture().use { f ->
                File(f.directory,name).writeText("tampered")
                val error = assertThrows(ModelResourceException::class.java) { f.loader().load() }
                assertEquals(expected,error.error.code,name)
                assertFalse(error.message.orEmpty().contains(f.root.path))
            }
        }
        ModelFixture().use { f -> File(f.directory,"model.int8.onnx").delete();assertEquals(ErrorCode.MODEL_MISSING,assertThrows(ModelResourceException::class.java) { f.loader().load() }.error.code) }
    }
    @Test fun `mixed manifest cannot substitute its own model hashes or runtime`() {
        ModelFixture().use { f ->
            val modified = f.catalog.deepCopy();modified.addProperty("runtimeRevision","bad-version")
            File(f.directory,"manifest.json").writeText(modified.toString())
            assertThrows(ModelResourceException::class.java) { f.loader().load() }
        }
    }
}
