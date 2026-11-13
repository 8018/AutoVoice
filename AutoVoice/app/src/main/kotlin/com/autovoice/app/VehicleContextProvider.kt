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

    /** A saved address needs a fresher and more precise fix than a nearby search. */
    fun usableForSavedAddress(ageMs: Long, accuracyMeters: Float): Boolean =
        ageMs in 0..30_000 && accuracyMeters.isFinite() && accuracyMeters in 0f..50f
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
    private var requestingAddressFix = false
    private val timeout = Runnable {
        val forSavedAddress = requestingAddressFix
        stopRefresh()
        val hint = when {
            latestUsablePosition(forSavedAddress) != null -> null
            forSavedAddress -> "未取得近期精确定位，请稍后重试"
            else -> "未取得有效定位，请在导航中说明城市"
        }
        onStatus(hint)
    }

    fun stopRefresh() {
        handler.removeCallbacks(timeout)
        listener?.let { current ->
            runCatching { (context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)?.removeUpdates(current) }
        }
        listener = null
        requestingAddressFix = false
    }

    /** Bounded foreground refresh; never delays speech or fabricates a default location. */
    @Suppress("DEPRECATION")
    fun refresh(forSavedAddress: Boolean = false) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { refresh(forSavedAddress) }
            return
        }
        // Repeated VAD segments must not keep restarting the bounded location request.
        if (listener != null) {
            if (!forSavedAddress || requestingAddressFix) return
            stopRefresh()
        }
        if (context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            onStatus("未授权定位，请开启定位权限，或在导航中说明城市")
            return
        }
        if (latestUsablePosition(forSavedAddress) != null) { onStatus(null); return }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        onStatus("定位不可用或已过期，正在刷新…")
        val current = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (listener !== this) return
                if (usable(location, forSavedAddress)) { stopRefresh(); onStatus(null) }
            }
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
            @Deprecated("Legacy Android callback")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
        }
        listener = current
        requestingAddressFix = forSavedAddress
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

    private fun usable(location: Location, forSavedAddress: Boolean): Boolean {
        if (!location.hasAccuracy()) return false
        val elapsedNanos = SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
        if (elapsedNanos < 0) return false
        val ageMs = elapsedNanos / 1_000_000
        return if (forSavedAddress) LocationQuality.usableForSavedAddress(ageMs, location.accuracy)
            else LocationQuality.usable(ageMs, location.accuracy)
    }

    override fun snapshot(): VehicleContext = VehicleContext(position = latestUsablePosition())

    fun savedAddressPosition(): VehiclePosition? = latestUsablePosition(forSavedAddress = true)

    /** Routing and saved addresses use the same precise, recent device fix. */
    fun routeOriginPosition(): VehiclePosition? = savedAddressPosition()

    private fun latestUsablePosition(forSavedAddress: Boolean = false): VehiclePosition? {
        val granted = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        return runCatching {
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            manager?.allProviders?.mapNotNull { provider ->
                runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            }?.filter { usable(it, forSavedAddress) }?.maxByOrNull { it.elapsedRealtimeNanos }
                ?.let { VehiclePosition(it.latitude, it.longitude) }
        }.getOrNull()
    }
}
