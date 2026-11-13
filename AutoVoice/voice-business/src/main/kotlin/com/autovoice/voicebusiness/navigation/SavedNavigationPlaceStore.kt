package com.autovoice.voicebusiness.navigation

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.security.MessageDigest

enum class SavedPlaceKind(val label: String) {
    HOME("家庭地址"), WORK("公司地址"),
}

/** GPS fixes and map search results use different coordinate systems. */
data class SavedNavigationPlace(
    val target: NavigationTarget,
    val isGpsCoordinate: Boolean = false,
) {
    override fun toString() = "SavedNavigationPlace(<redacted>)"
}

class SavedNavigationPlaceStore(private val storage: Storage) {
    interface Storage {
        fun read(key: String): String?
        /** A failed write must leave the previous value readable. Null removes the value. */
        fun write(key: String, value: String?)
    }

    class StorageUnavailable : RuntimeException("Saved navigation place storage unavailable")

    @Synchronized
    fun read(userId: String, kind: SavedPlaceKind): SavedNavigationPlace? = accessStorage {
        storage.read(key(userId, kind))?.let { raw ->
            val value = JsonParser.parseString(raw).asJsonObject
            require(value["version"].asInt == 1)
            SavedNavigationPlace(
                NavigationTarget(value["name"].asString, value["latitude"].asDouble, value["longitude"].asDouble),
                value["gps"].asBoolean,
            )
        }
    }

    @Synchronized
    fun save(userId: String, kind: SavedPlaceKind, place: SavedNavigationPlace) = accessStorage {
        val value = JsonObject().apply {
            addProperty("version", 1)
            addProperty("name", place.target.name)
            addProperty("latitude", place.target.latitude)
            addProperty("longitude", place.target.longitude)
            addProperty("gps", place.isGpsCoordinate)
        }
        storage.write(key(userId, kind), value.toString())
    }

    @Synchronized
    fun delete(userId: String, kind: SavedPlaceKind) = accessStorage {
        storage.write(key(userId, kind), null)
    }

    @Synchronized
    internal fun replaceIfCurrent(
        userId: String,
        kind: SavedPlaceKind,
        previous: SavedNavigationPlace?,
        next: SavedNavigationPlace?,
    ): Boolean {
        if (read(userId, kind) != previous) return false
        if (next == null) delete(userId, kind) else save(userId, kind, next)
        return true
    }

    private fun key(userId: String, kind: SavedPlaceKind): String {
        require(userId.isNotBlank())
        val hash = MessageDigest.getInstance("SHA-256").digest(userId.toByteArray(Charsets.UTF_8))
        return "${hash.joinToString("") { "%02x".format(it) }}-${kind.name}"
    }

    private inline fun <T> accessStorage(block: () -> T): T = try {
        block()
    } catch (_: Exception) {
        // Backend and parser errors can include the raw record.
        throw StorageUnavailable()
    }
}
