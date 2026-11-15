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

    private fun fix(provider: String, lat: Double, lon: Double, accuracy: Float, ageMs: Long = 0) =
        Location(provider).apply {
            latitude = lat
            longitude = lon
            this.accuracy = accuracy
            time = System.currentTimeMillis() - ageMs
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }

    /** Robolectric hands simulated fixes to listeners through the main looper; run it. */
    private fun deliver(location: Location) {
        shadowOf(lm).simulateLocation(location)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun advance(seconds: Long) =
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))

    private val gps = LocationManager.GPS_PROVIDER
    private val net = LocationManager.NETWORK_PROVIDER

    // ---- precise -----------------------------------------------------------------------

    @Test
    fun accurateGpsFixIsAcceptedImmediatelyAsExact() {
        val t = tracker().also { it.start() }
        assertEquals(LocState.Searching, last)
        deliver(fix(gps, 52.52, 13.405, 12f))
        val f = last as LocState.Fix
        assertEquals(52.52, f.latitude, 1e-9)
        assertEquals(12f, f.accuracy, 0f)
        assertFalse("a precise fresh GPS fix is not approximate", f.approximate)
        t.stop()
    }

    @Test
    fun aPreciseGpsFixUpgradesAnEarlierRoughResultAndEndsTheSearch() {
        val t = tracker().also { it.start() }
        deliver(fix(net, 1.0, 2.0, 800f))
        assertTrue((last as LocState.Fix).approximate)
        deliver(fix(gps, 1.001, 2.001, 8f))
        val f = last as LocState.Fix
        assertFalse(f.approximate)
        assertEquals(1.001, f.latitude, 1e-9)
        val n = states.size
        deliver(fix(net, 9.0, 9.0, 50f)) // search is over: ignored
        assertEquals(n, states.size)
        t.stop()
    }

    // ---- rough is enough, and fast -----------------------------------------------------

    @Test
    fun aRoughNetworkFixIsAcceptedAtOnceWithoutWaiting() {
        tracker().start()
        deliver(fix(net, 3.0, 4.0, 800f))
        val f = last as LocState.Fix
        assertTrue(f.approximate)
        assertEquals(3.0, f.latitude, 1e-9)
    }

    @Test
    fun aVagueGpsFixIsAlsoAcceptedAtOnce() {
        tracker().start()
        deliver(fix(gps, 1.0, 2.0, 400f))
        assertTrue((last as LocState.Fix).approximate)
    }

    @Test
    fun networkFixIsNeverTreatedAsExactEvenIfItClaimsHighAccuracy() {
        tracker().start()
        deliver(fix(net, 3.0, 4.0, 20f))
        assertTrue((last as LocState.Fix).approximate)
    }

    @Test
    fun aRecentLastKnownLocationEnablesSaveInstantly() {
        shadowOf(lm).setLastKnownLocation(gps, fix(gps, 48.85, 2.35, 30f, ageMs = 120_000))
        tracker().start()
        // No looper advance, no waiting: the very first thing the user sees is a usable fix.
        val f = last as LocState.Fix
        assertEquals(48.85, f.latitude, 1e-9)
        assertTrue(f.approximate)
    }

    @Test
    fun aFreshFixReplacesTheInterimLastKnownLocation() {
        shadowOf(lm).setLastKnownLocation(gps, fix(gps, 48.85, 2.35, 30f, ageMs = 120_000))
        tracker().start()
        deliver(fix(net, 10.0, 20.0, 900f)) // vaguer, but fresh: where you are now
        assertEquals(10.0, (last as LocState.Fix).latitude, 1e-9)
    }

    @Test
    fun betterRoughFixesReplaceWorseOnesButNotTheReverse() {
        tracker().start()
        // Spaced out: the tracker asks providers for at most one update per second.
        deliver(fix(net, 1.0, 1.0, 3000f))
        advance(2)
        deliver(fix(net, 2.0, 2.0, 800f))
        assertEquals(2.0, (last as LocState.Fix).latitude, 1e-9)
        advance(2)
        deliver(fix(net, 3.0, 3.0, 2000f))
        assertEquals("a worse fix must not replace a better one", 2.0, (last as LocState.Fix).latitude, 1e-9)
    }

    // ---- fallbacks ---------------------------------------------------------------------

    @Test
    fun aFixTooVagueToAcceptKeepsSearchingThenIsUsedAfterTheTimeout() {
        tracker().start()
        deliver(fix(net, 5.0, 6.0, 9000f))
        assertEquals(LocState.Searching, last)
        advance(7)
        assertEquals("must still be waiting at 7 s", LocState.Searching, last)
        advance(2)
        val f = last as LocState.Fix
        assertTrue(f.approximate)
        assertEquals(5.0, f.latitude, 1e-9)
    }

    @Test
    fun aStaleLastKnownLocationIsOnlyUsedAsALastResortAfterTheTimeout() {
        shadowOf(lm).setLastKnownLocation(gps, fix(gps, 48.85, 2.35, 30f, ageMs = 3_600_000))
        tracker().start()
        assertEquals("an hour-old position isn't offered straight away", LocState.Searching, last)
        advance(7)
        assertEquals(LocState.Searching, last)
        advance(2)
        val f = last as LocState.Fix
        assertEquals(48.85, f.latitude, 1e-9)
        assertTrue(f.approximate)
    }

    @Test
    fun nothingAtAllEndsInNoFixSoNothingCanBeSaved() {
        tracker().start()
        advance(9)
        assertEquals(LocState.NoFix, last)
    }

    // ---- permissions / switches --------------------------------------------------------

    @Test
    fun approximateOnlyPermissionStillWorks() {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        tracker().start()
        assertEquals("coarse permission alone is enough", LocState.Searching, last)
        deliver(fix(net, 7.0, 8.0, 1200f))
        assertEquals(7.0, (last as LocState.Fix).latitude, 1e-9)
    }

    @Test
    fun noLocationPermissionAtAllIsReported() {
        shadowOf(app).denyPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        tracker().start()
        assertEquals(LocState.NoPermission, last)
    }

    @Test
    fun locationSwitchedOffIsReported() {
        shadowOf(lm).setLocationEnabled(false)
        tracker().start()
        assertEquals(LocState.LocationOff, last)
    }

    @Test
    fun stoppingCancelsTheTimeoutAndDropsListeners() {
        val t = tracker().also { it.start() }
        t.stop()
        val before = states.size
        advance(30)
        deliver(fix(gps, 5.0, 6.0, 5f))
        assertEquals("a stopped tracker must stay silent", before, states.size)
    }
}
