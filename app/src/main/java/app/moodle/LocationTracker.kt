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
        /** True unless this is a fresh, precise (<= 100 m) GPS fix. Rough is fine for saving. */
        val approximate: Boolean,
    ) : LocState

    data object LocationOff : LocState
    data object NoPermission : LocState
    data object NoFix : LocState
}

/**
 * Gets a *rough* position quickly, using only the platform [LocationManager] (no Google Play
 * Services, no network access from this app). Speed beats precision here:
 *
 * 1. A last-known location younger than [INTERIM_MAX_AGE_MS] is offered immediately, so Save is
 *    available the moment the screen opens. Anything fresher replaces it.
 * 2. Any fresh fix within [ROUGH_OK_M] (network, fused, GPS, or another app's passive fix) is
 *    accepted right away; a precise GPS fix (<= [GOOD_ACCURACY_M]) ends the search as exact.
 * 3. If nothing usable has appeared after [TIMEOUT_MS], the best fresh fix or the last-known
 *    location of any age is used, flagged approximate.
 * 4. If there is nothing at all, the state becomes [LocState.NoFix] and the UI offers Retry.
 *
 * Works with the coarse (approximate) location permission alone; GPS is added when precise
 * permission is also granted.
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

    /** The fix currently offered to the UI (if any) and whether it is a fresh one. */
    private var adopted: Location? = null
    private var adoptedIsFresh = false

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasFine() = granted(Manifest.permission.ACCESS_FINE_LOCATION)
    private fun hasAnyPermission() = hasFine() || granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    @SuppressLint("MissingPermission") // guarded by hasAnyPermission()
    fun start() {
        stop()
        fresh.clear()
        adopted = null
        adoptedIsFresh = false
        if (!hasAnyPermission()) {
            onState(LocState.NoPermission); return
        }
        if (!lm.isLocationEnabled) {
            onState(LocState.LocationOff); return
        }
        val providers = buildList {
            if (hasFine()) add(LocationManager.GPS_PROVIDER)
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            // Free fixes other apps are already getting.
            add(LocationManager.PASSIVE_PROVIDER)
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
                // e.g. GPS with only the approximate permission; the other providers still work.
            } catch (_: IllegalArgumentException) {
            }
        }
        if (listeners.isEmpty()) {
            onState(LocState.LocationOff); return
        }

        // Instant start: a recent last-known position is good enough to enable Save at once.
        lastKnown()?.takeIf { ageMs(it) <= INTERIM_MAX_AGE_MS }?.let { adopt(it, isFresh = false) }

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
        val accuracy = accuracyOf(location)
        val preciseGps = location.provider != LocationManager.NETWORK_PROVIDER &&
            (accuracy <= GOOD_ACCURACY_M ||
                (!location.hasAccuracy() && location.provider == LocationManager.GPS_PROVIDER))
        if (preciseGps) {
            finish(location, approximate = false)
        } else if (accuracy <= ROUGH_OK_M) {
            adopt(location, isFresh = true)
        }
    }

    /** Offers [location] to the UI unless something at least as good is already showing. */
    private fun adopt(location: Location, isFresh: Boolean) {
        val current = adopted
        if (current != null) {
            val upgrade = isFresh && !adoptedIsFresh
            if (!upgrade && accuracyOf(current) <= accuracyOf(location)) return
        }
        adopted = location
        adoptedIsFresh = isFresh
        onState(location.toFix(approximate = true))
    }

    private fun finishBestEffort() {
        if (adopted != null) {
            stop() // something usable is already on screen
            return
        }
        val best = fresh.minByOrNull { accuracyOf(it) } ?: lastKnown()
        if (best != null) {
            finish(best, approximate = true)
        } else {
            stop()
            onState(LocState.NoFix)
        }
    }

    /** Freshest last-known fix of any provider, including passive (other apps') ones. */
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
        adopted = location
        onState(location.toFix(approximate))
    }

    private fun accuracyOf(location: Location) =
        if (location.hasAccuracy()) location.accuracy else Float.MAX_VALUE

    private fun ageMs(location: Location) = System.currentTimeMillis() - location.time

    private fun Location.toFix(approximate: Boolean) = LocState.Fix(
        latitude = latitude,
        longitude = longitude,
        accuracy = if (hasAccuracy()) accuracy else -1f,
        approximate = approximate,
    )

    companion object {
        /** How long to look for something usable before falling back to whatever exists. */
        const val TIMEOUT_MS = 8_000L

        /** A fresh fix this close (metres) ends the search as an exact GPS fix. */
        const val GOOD_ACCURACY_M = 100f

        /** A fresh fix this vague is still accepted immediately: rough is enough. */
        const val ROUGH_OK_M = 5_000f

        /** A last-known location up to this old is offered immediately. */
        const val INTERIM_MAX_AGE_MS = 15 * 60 * 1000L
    }
}
