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
    @Test fun `actual pinned model ID with underscore validates tiny fixed resource fixture`() {
        ModelFixture().use { f ->
            val modelId = FireRedModelCatalog.read().get("modelId").asString
            assertEquals("sherpa-onnx-fire-red-asr2-ctc-zh_en-int8-2026-02-25",modelId)
            f.catalog.addProperty("modelId",modelId)
            val target = File(f.root,"$modelId/${f.catalog.get("exporterRevision").asString}")
            target.parentFile.mkdirs()
            Files.move(f.directory.toPath(),target.toPath())
            File(target,"manifest.json").writeText(f.catalog.toString())
            val verified = f.loader().load()
            assertEquals(File(target,"model.int8.onnx").canonicalFile,verified.modelFile)
            assertEquals(modelId,verified.metadata["modelId"])
        }
    }
    @Test fun `unsafe catalog identifiers still fail validation before resource lookup`() {
        for (unsafe in listOf("../fixture-ctc","fixture/ctc","fixture\\ctc","fixture.ctc","Fixture-ctc","fixture ctc","fixture:ctc","")) {
            ModelFixture().use { f ->
                f.catalog.addProperty("modelId",unsafe)
                assertEquals(ErrorCode.MODEL_HASH_MISMATCH,assertThrows(ModelResourceException::class.java) { f.loader().load() }.error.code,unsafe)
            }
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
