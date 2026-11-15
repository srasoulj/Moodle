package app.moodle

import android.Manifest
import android.app.Application
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
class LocationTrackerTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val lm get() = app.getSystemService(LocationManager::class.java)
    private val states = mutableListOf<LocState>()
    private val last get() = states.last()

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        shadowOf(lm).setLocationEnabled(true)
        shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowOf(lm).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
    }

    private fun tracker() = LocationTracker(app) { states += it }

    private fun fix(provider: String, lat: Double, lon: Double, accuracy: Float) =
        Location(provider).apply {
            latitude = lat
            longitude = lon
            this.accuracy = accuracy
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }

    /** Robolectric hands simulated fixes to listeners through the main looper; run it. */
    private fun deliver(location: Location) {
        shadowOf(lm).simulateLocation(location)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun advance(seconds: Long) =
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))

    @Test
    fun accurateGpsFixIsAcceptedImmediatelyAsExact() {
        val t = tracker().also { it.start() }
        assertEquals(LocState.Searching, last)
        deliver(fix(LocationManager.GPS_PROVIDER, 52.52, 13.405, 12f))
        val f = last as LocState.Fix
        assertEquals(52.52, f.latitude, 1e-9)
        assertEquals(12f, f.accuracy, 0f)
        assertFalse("a fresh accurate GPS fix is not approximate", f.approximate)
        t.stop()
    }

    @Test
    fun aVagueFixKeepsSearchingThenFallsBackToItAsApproximate() {
        tracker().start()
        deliver(fix(LocationManager.GPS_PROVIDER, 1.0, 2.0, 400f))
        assertEquals("400 m is too vague to accept yet", LocState.Searching, last)
        advance(21)
        val f = last as LocState.Fix
        assertTrue(f.approximate)
        assertEquals(1.0, f.latitude, 1e-9)
    }

    @Test
    fun networkFixIsNeverTreatedAsExactEvenIfAccurate() {
        tracker().start()
        deliver(fix(LocationManager.NETWORK_PROVIDER, 3.0, 4.0, 20f))
        assertEquals(LocState.Searching, last)
        advance(21)
        assertTrue((last as LocState.Fix).approximate)
    }

    @Test
    fun noFreshFixInTwentySecondsUsesLastKnownMarkedApproximate() {
        shadowOf(lm).setLastKnownLocation(
            LocationManager.GPS_PROVIDER, fix(LocationManager.GPS_PROVIDER, 48.85, 2.35, 30f),
        )
        tracker().start()
        advance(19)
        assertEquals("must still be waiting at 19 s", LocState.Searching, last)
        advance(2)
        val f = last as LocState.Fix
        assertEquals(48.85, f.latitude, 1e-9)
        assertTrue(f.approximate)
    }

    @Test
    fun nothingAtAllEndsInNoFixSoNothingCanBeSaved() {
        tracker().start()
        advance(21)
        assertEquals(LocState.NoFix, last)
    }

    @Test
    fun locationSwitchedOffIsReported() {
        shadowOf(lm).setLocationEnabled(false)
        tracker().start()
        assertEquals(LocState.LocationOff, last)
    }

    @Test
    fun missingPermissionIsReported() {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        tracker().start()
        assertEquals(LocState.NoPermission, last)
    }

    @Test
    fun stoppingCancelsTheTimeoutAndDropsListeners() {
        val t = tracker().also { it.start() }
        t.stop()
        val before = states.size
        advance(30)
        deliver(fix(LocationManager.GPS_PROVIDER, 5.0, 6.0, 5f))
        assertEquals("a stopped tracker must stay silent", before, states.size)
    }
}
