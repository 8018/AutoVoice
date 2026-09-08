package com.autovoice.app

import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.location.Location
import android.location.LocationListener
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** Use monotonic age so clock changes cannot make a stale fix appear recent. */
object LocationQuality {
    fun usable(ageMs: Long, accuracyMeters: Float): Boolean =
        ageMs in 0..120_000 && accuracyMeters.isFinite() && accuracyMeters in 0f..500f
}

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

/** Reads fresh, accurate phone fixes and supports bounded foreground refreshes. */
class PhoneVehicleContextProvider(
    private val context: Context,
    private val onStatus: (String?) -> Unit = {},
) : VehicleContextProvider {
    private val handler = Handler(Looper.getMainLooper())
    private var listener: LocationListener? = null
    private val timeout = Runnable { stopRefresh(); onStatus(if (snapshot().position == null) "未取得有效定位，请在导航中说明城市" else null) }

    fun stopRefresh() {
        handler.removeCallbacks(timeout)
        listener?.let { current ->
            runCatching { (context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)?.removeUpdates(current) }
        }
        listener = null
    }

    /** Bounded foreground refresh; never delays speech or fabricates a default location. */
    @Suppress("DEPRECATION")
    fun refresh() {
        stopRefresh()
        if (context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            onStatus("未授权定位，请开启定位权限，或在导航中说明城市")
            return
        }
        if (snapshot().position != null) { onStatus(null); return }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        onStatus("定位不可用或已过期，正在刷新…")
        val current = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (listener !== this) return
                if (usable(location)) { stopRefresh(); onStatus(null) }
            }
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
            @Deprecated("Legacy Android callback")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
        }
        listener = current
        var requested = false
        for (provider in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)) {
            requested = runCatching {
                if (manager.isProviderEnabled(provider)) {
                    manager.requestLocationUpdates(provider, 1000L, 0f, current, Looper.getMainLooper())
                    true
                } else false
            }.getOrDefault(false) || requested
        }
        if (requested) handler.postDelayed(timeout, 8000L)
        else { stopRefresh(); onStatus("无法获取定位，请开启定位权限与系统定位，或说明城市") }
    }

    private fun usable(location: Location) = location.hasAccuracy() && LocationQuality.usable(
        (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000,
        location.accuracy,
    )
    override fun snapshot(): VehicleContext {
        val granted = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!granted) return VehicleContext()
        val position = runCatching {
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            manager?.allProviders?.mapNotNull { provider ->
                runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            }?.filter { usable(it) }?.maxByOrNull { it.elapsedRealtimeNanos }?.let { VehiclePosition(it.latitude, it.longitude) }
        }.getOrNull()
        return VehicleContext(position = position)
    }
}
