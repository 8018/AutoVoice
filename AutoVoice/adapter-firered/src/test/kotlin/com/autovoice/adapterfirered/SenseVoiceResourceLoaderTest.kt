package com.autovoice.adapterfirered

import com.autovoice.voicecore.health.ErrorCode
import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class SenseVoiceResourceLoaderTest {
    @Test fun `packaged resources use the measured SenseVoice and Silero revisions`() {
        val catalog = SenseVoiceModelCatalog.read()
        assertEquals("sensevoice.int8", catalog.get("provider").asString)
        assertEquals("2365baeacb507f821a0c8120fcee3d484dba7a07", catalog.get("sourceRevision").asString)
        assertEquals("066409f3d9db45a30d21dfcbf9147826ffdd7052", catalog.get("vadSourceRevision").asString)
        val files = catalog.getAsJsonObject("files")
        assertEquals("c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51",
            files.getAsJsonObject("model").get("sha256").asString)
        assertEquals("1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3",
            files.getAsJsonObject("vad").get("sha256").asString)
    }

    @Test fun `verified graph and tokens paths and provenance come from the packaged catalog`() {
        Fixture().use { f ->
            File(f.root, "manifest.json").writeText("""{"modelId":"untrusted","files":{}}""")
            val verified = f.load()
            assertEquals(File(f.root, "models/sensevoice/model.int8.onnx").canonicalFile, verified.modelFile)
            assertEquals(File(f.root, "models/sensevoice/tokens.txt").canonicalFile, verified.tokensFile)
            assertEquals(File(f.root, "models/vad/silero_vad.onnx").canonicalFile, verified.vadFile)
            assertEquals("sensevoice.int8", verified.metadata["provider"])
            assertEquals("2.0.1", verified.metadata["runtime"])
            assertEquals("1.28.2", verified.metadata["ort"])
            assertEquals(f.pins("vad").get("sha256").asString, verified.metadata["vadSha256"])
        }
    }

    @Test fun `all five resources must exist and match their pinned hashes`() {
        val errors = mapOf(
            "model" to (ErrorCode.MODEL_MISSING to ErrorCode.MODEL_HASH_MISMATCH),
            "tokens" to (ErrorCode.TOKENS_MISMATCH to ErrorCode.TOKENS_MISMATCH),
            "modelLicense" to (ErrorCode.RESOURCE_MISSING to ErrorCode.RESOURCE_MISSING),
            "vad" to (ErrorCode.RESOURCE_MISSING to ErrorCode.MODEL_HASH_MISMATCH),
            "vadLicense" to (ErrorCode.RESOURCE_MISSING to ErrorCode.RESOURCE_MISSING),
        )
        for ((role, expected) in errors) {
            Fixture().use { f ->
                val file = f.file(role)
                val original = file.readBytes()
                file.writeBytes(original.copyOf().apply { this[0] = 0 })
                assertEquals(expected.second, assertThrows(ModelResourceException::class.java) { f.load() }.error.code, role)
                assertTrue(file.delete())
                assertEquals(expected.first, assertThrows(ModelResourceException::class.java) { f.load() }.error.code, role)
            }
        }
    }

    @Test fun `resource symlinks cannot substitute a pinned graph`() {
        Fixture().use { f ->
            val file = f.file("vad")
            val alternate = File(f.root, "unverified-vad.onnx").apply { writeBytes(file.readBytes()) }
            assertTrue(file.delete())
            Files.createSymbolicLink(file.toPath(), alternate.toPath())
            assertEquals(ErrorCode.RESOURCE_MISSING,
                assertThrows(ModelResourceException::class.java) { f.load() }.error.code)
        }
    }

    private class Fixture : AutoCloseable {
        val root = Files.createTempDirectory("sensevoice-model-fixture").toFile()
        private val catalog = SenseVoiceModelCatalog.read()
        private val files = catalog.getAsJsonObject("files")

        init {
            for ((role, value) in files.entrySet()) {
                val pin = value.asJsonObject
                val bytes = role.toByteArray()
                file(role).apply { requireNotNull(parentFile).mkdirs(); writeBytes(bytes) }
                pin.addProperty("bytes", bytes.size)
                pin.addProperty("sha256", MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it.toInt() and 255) })
            }
        }

        fun pins(role: String): JsonObject = files.getAsJsonObject(role)
        fun file(role: String) = File(root, pins(role).get("path").asString)
        fun load() = SenseVoiceResourceLoader(root) { catalog.deepCopy() }.load()
        override fun close() { root.deleteRecursively() }
    }
}
