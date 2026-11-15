package app.moodle

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

sealed interface LocState {
    data object Idle : LocState
    data object Searching : LocState
    data class Fix(
        val latitude: Double,
        val longitude: Double,
        /** Metres, or negative when the provider didn't report one. */
        val accuracy: Float,
        val approximate: Boolean,
    ) : LocState

    data object LocationOff : LocState
    data object NoPermission : LocState
    data object NoFix : LocState
}

/**
 * Gets a position using only the platform [LocationManager] (no Google Play Services, no
 * network access from this app).
 *
 * 1. A fresh fix of <= [GOOD_ACCURACY_M] from GPS/fused is accepted immediately as exact.
 * 2. If none arrives within [TIMEOUT_MS], the best fresh fix we did get, otherwise the
 *    last-known location, is used and flagged `approximate`.
 * 3. If there is nothing at all the state becomes [LocState.NoFix] and the UI offers Retry.
 */
class LocationTracker(
    private val context: Context,
    private val onState: (LocState) -> Unit,
) {
    private val lm = context.getSystemService(LocationManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<LocationListener>()
    private val fresh = mutableListOf<Location>()
    private val timeoutRunnable = Runnable { finishBestEffort() }

    private fun hasFinePermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // guarded by hasFinePermission()
    fun start() {
        stop()
        fresh.clear()
        if (!hasFinePermission()) {
            onState(LocState.NoPermission); return
        }
        if (!lm.isLocationEnabled) {
            onState(LocState.LocationOff); return
        }
        val providers = buildList {
            add(LocationManager.GPS_PROVIDER)
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }.filter { it in lm.allProviders && lm.isProviderEnabled(it) }
        if (providers.isEmpty()) {
            onState(LocState.LocationOff); return
        }

        onState(LocState.Searching)
        for (provider in providers) {
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) = handleFix(location)

                // Implemented explicitly: these are default methods on API 30+ but abstract
                // on the API 28/29 devices we still support.
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}

                @Suppress("OVERRIDE_DEPRECATION")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            }
            try {
                lm.requestLocationUpdates(provider, 1000L, 0f, listener, Looper.getMainLooper())
                listeners += listener
            } catch (_: SecurityException) {
            } catch (_: IllegalArgumentException) {
            }
        }
        if (listeners.isEmpty()) {
            onState(LocState.LocationOff); return
        }
        handler.postDelayed(timeoutRunnable, TIMEOUT_MS)
    }

    fun stop() {
        handler.removeCallbacks(timeoutRunnable)
        listeners.forEach { runCatching { lm.removeUpdates(it) } }
        listeners.clear()
    }

    private fun handleFix(location: Location) {
        if (listeners.isEmpty()) return // already finished
        fresh += location
        val accurate = if (location.hasAccuracy()) {
            location.accuracy <= GOOD_ACCURACY_M
        } else {
            location.provider == LocationManager.GPS_PROVIDER
        }
        if (accurate && location.provider != LocationManager.NETWORK_PROVIDER) {
            finish(location, approximate = false)
        }
    }

    private fun finishBestEffort() {
        val best = fresh.minByOrNull { if (it.hasAccuracy()) it.accuracy else Float.MAX_VALUE }
            ?: lastKnown()
        if (best != null) {
            finish(best, approximate = true)
        } else {
            stop()
            onState(LocState.NoFix)
        }
    }

    @SuppressLint("MissingPermission") // permission was checked in start()
    private fun lastKnown(): Location? = try {
        lm.getProviders(false)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    } catch (_: SecurityException) {
        null
    }

    private fun finish(location: Location, approximate: Boolean) {
        stop()
        onState(
            LocState.Fix(
                latitude = location.latitude,
                longitude = location.longitude,
                accuracy = if (location.hasAccuracy()) location.accuracy else -1f,
                approximate = approximate,
            )
        )
    }

    companion object {
        const val TIMEOUT_MS = 20_000L
        const val GOOD_ACCURACY_M = 100f
    }
}
