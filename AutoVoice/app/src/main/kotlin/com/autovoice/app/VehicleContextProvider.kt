package com.autovoice.app

import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager

data class VehiclePosition(val latitude: Double, val longitude: Double) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
    }
}

/** Missing vehicle data stays unknown. Tests may inject a fixed context without overriding GPS. */
data class VehicleContext(val position: VehiclePosition? = null, val socPercent: Double? = null) {
    init { require(socPercent == null || (socPercent.isFinite() && socPercent in 0.0..100.0)) }
}

fun interface VehicleContextProvider { fun snapshot(): VehicleContext }

/** Uses the same last-known phone location policy as the existing production path. */
class PhoneVehicleContextProvider(private val context: Context) : VehicleContextProvider {
    override fun snapshot(): VehicleContext {
        val granted = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!granted) return VehicleContext()
        val position = runCatching {
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            manager?.allProviders?.mapNotNull { provider ->
                runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            }?.maxByOrNull { it.time }?.let { VehiclePosition(it.latitude, it.longitude) }
        }.getOrNull()
        return VehicleContext(position = position)
    }
}
