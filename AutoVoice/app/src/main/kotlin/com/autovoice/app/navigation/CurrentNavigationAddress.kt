package com.autovoice.app.navigation

import android.content.Context
import android.location.Geocoder
import com.autovoice.app.VehiclePosition
import com.autovoice.voicebusiness.navigation.NavigationTarget
import com.autovoice.voicebusiness.navigation.SavedNavigationPlace
import java.util.Locale

/** Reverse geocoding supplies the display address. The confirmed GPS point remains the destination. */
internal class CurrentNavigationAddress(private val context: Context) {
    @Suppress("DEPRECATION") // API 26–32 use the synchronous platform method on an I/O dispatcher.
    fun resolve(position: VehiclePosition): SavedNavigationPlace? {
        if (!Geocoder.isPresent()) return null
        val address = try {
            Geocoder(context, Locale.getDefault())
                .getFromLocation(position.latitude, position.longitude, 1)
                ?.firstOrNull()
                ?.getAddressLine(0)
                ?.trim()
                ?.takeIf(String::isNotEmpty)
        } catch (_: Exception) {
            // Geocoder failures can include the lookup coordinates.
            null
        } ?: return null

        return SavedNavigationPlace(
            NavigationTarget(address, position.latitude, position.longitude),
            isGpsCoordinate = true,
        )
    }
}
