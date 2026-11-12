package com.autovoice.app.audio

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.autovoice.adapterfirered.FireRedModelCatalog
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Prepares fixed catalog paths as the target App UID, for an operator to copy verified files. */
@RunWith(AndroidJUnit4::class)
class OpenModelProvisioningTest {
    @Test
    fun prepareCatalogPaths() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = checkNotNull(instrumentation.targetContext.getExternalFilesDir("speech-models")) {
            "App external model directory is unavailable"
        }.canonicalFile
        val catalog = FireRedModelCatalog.read()
        val modelId = catalog.get("modelId").asString
        val revision = catalog.get("exporterRevision").asString
        val version = File(root, "$modelId/$revision")
        val names = listOf("manifest.json") + catalog.getAsJsonObject("files").keySet().sorted()
        for (name in names) {
            val file = File(version, name)
            check(file.canonicalFile == file && file.path.startsWith(root.path + File.separator)) {
                "Model resource is outside the fixed catalog path"
            }
            val directory = checkNotNull(file.parentFile)
            check(directory.isDirectory || directory.mkdirs()) { "Could not prepare model directory" }
            check(file.isFile || file.createNewFile()) { "Could not prepare catalog member $name" }
        }
        instrumentation.sendStatus(0, Bundle().apply {
            putString("modelCatalog", version.path)
            putInt("catalogFiles", names.size)
        })
    }
}
