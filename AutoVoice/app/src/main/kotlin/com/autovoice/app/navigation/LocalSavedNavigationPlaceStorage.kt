package com.autovoice.app.navigation

import com.autovoice.voicebusiness.navigation.SavedNavigationPlaceStore
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption

/** The application supplies its private no-backup directory. */
internal class LocalSavedNavigationPlaceStorage(
    private val directory: () -> File,
) : SavedNavigationPlaceStore.Storage {
    override fun read(key: String): String? = try {
        String(Files.readAllBytes(directory().resolve(key).toPath()), Charsets.UTF_8)
    } catch (_: NoSuchFileException) {
        null
    }

    override fun write(key: String, value: String?) {
        val destination = directory().resolve(key).toPath()
        if (value == null) {
            Files.deleteIfExists(destination)
            return
        }

        val parent = Files.createDirectories(destination.parent)
        val temporary = Files.createTempFile(parent, "place-", ".tmp")
        try {
            FileOutputStream(temporary.toFile()).use { output ->
                output.write(value.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
