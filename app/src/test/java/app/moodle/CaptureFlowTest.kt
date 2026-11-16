package app.moodle

import android.Manifest
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import androidx.camera.core.ImageCapture
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28, 35])
class CaptureFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val lm get() = app.getSystemService(LocationManager::class.java)
    private val db get() = MoodDb.get(app)

    @Before
    fun setUp() {
        MoodDb.reset()
        shadowOf(lm).setLocationEnabled(true)
        shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowOf(lm).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
    }

    @After fun tearDown() = MoodDb.reset()

    private fun grantAll() = shadowOf(app).grantPermissions(
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    private fun intentFor(mood: Mood) = Intent(app, CaptureActivity::class.java)
        .setAction(CaptureActivity.ACTION_CAPTURE)
        .putExtra(CaptureActivity.EXTRA_MOOD, mood.id)

    private fun launch(mood: Mood) =
        Robolectric.buildActivity(CaptureActivity::class.java, intentFor(mood)).setup()

    private fun fakeSelfie(): File = Photos.newPendingFile(app).also { f ->
        f.outputStream().use { Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 80, it) }
    }

    private fun gps(lat: Double, lon: Double, accuracy: Float) = Location(LocationManager.GPS_PROVIDER).apply {
        latitude = lat; longitude = lon; this.accuracy = accuracy
        time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
    }

    /**
     * Waits for work done on a background thread (the save), which reports back by posting to the
     * main looper. Robolectric's looper is paused, so something has to pump it, but only while
     * Compose is idle: pumping mid-layout is what made an earlier version of this flaky.
     */
    private fun waitFor(timeoutMs: Long = 5000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < end) {
            compose.runOnIdle { shadowOf(Looper.getMainLooper()).idle() }
            Thread.sleep(25)
        }
        assertTrue("condition not met in ${timeoutMs}ms", cond())
    }

    @Test
    fun withoutCameraAndLocationPermissionItAsksAndSavesNothing() {
        val c = launch(Mood.LOVE)
        compose.onNodeWithText("Allow access").assertExists()
        compose.onNodeWithText("🥰  Love").assertExists()
        assertEquals(0, db.count())
        c.pause().stop().destroy()
    }

    /**
     * CameraX binds asynchronously, after the first composition. The shutter must notice, or no
     * selfie (and therefore no moment) can ever be taken on a real phone.
     */
    @Test
    fun shutterBecomesEnabledOnceTheCameraHasBound() {
        grantAll()
        val c = launch(Mood.GOOD)
        compose.onNodeWithContentDescription("Take selfie").assertIsNotEnabled()
        c.get().imageCapture = ImageCapture.Builder().build()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Take selfie").assertIsEnabled()
        c.pause().stop().destroy()
    }

    @Test
    fun savingIsBlockedUntilThereIsALocation() {
        grantAll()
        val c = launch(Mood.EXCITED)
        c.get().pendingFile = fakeSelfie()
        compose.onNodeWithText("Save moment").assertIsNotEnabled()
        compose.onNodeWithText("📍 Finding your location…").assertExists()
        shadowOf(lm).simulateLocation(gps(52.52, 13.405, 6f))
        compose.onNodeWithText("Save moment").assertIsEnabled()
        c.pause().stop().destroy()
    }

    @Test
    fun saveStoresMoodTimeSelfieAndGpsTogether() {
        grantAll()
        val before = System.currentTimeMillis()
        val c = launch(Mood.EXCITED)
        val pending = fakeSelfie().also { c.get().pendingFile = it }
        shadowOf(lm).simulateLocation(gps(52.52, 13.405, 6f))

        compose.onNodeWithText("Save moment").assertIsEnabled().performClick()
        waitFor { db.count() == 1 }

        val m = db.between(0, Long.MAX_VALUE).single()
        assertEquals(Mood.EXCITED, m.mood)
        assertEquals(52.52, m.latitude, 1e-9)
        assertEquals(13.405, m.longitude, 1e-9)
        assertEquals(6f, m.accuracyMeters, 0f)
        assertFalse("fresh accurate GPS must not be flagged approximate", m.approximate)
        assertTrue("timestamp should be the tap time", m.epochMillis in before..System.currentTimeMillis())
        assertTrue("selfie must be stored with the moment", Photos.file(app, m.photoName).length() > 0)
        assertFalse("pending photo must have been moved, not copied", pending.exists())
        waitFor { c.get().isFinishing }
    }

    @Test
    fun aRecentLastKnownLocationLetsYouSaveStraightAway() {
        grantAll()
        shadowOf(lm).setLastKnownLocation(
            LocationManager.GPS_PROVIDER, gps(48.85, 2.35, 40f).apply { time = System.currentTimeMillis() - 60_000 },
        )
        val c = launch(Mood.GOOD)
        c.get().pendingFile = fakeSelfie()
        // No waiting at all: the rough location is already there.
        compose.onNodeWithText("📍 Rough location found").assertExists()
        compose.onNodeWithText("Save moment").assertIsEnabled().performClick()
        waitFor { db.count() == 1 }
        val m = db.between(0, Long.MAX_VALUE).single()
        assertTrue(m.approximate)
        assertEquals(48.85, m.latitude, 1e-9)
    }

    @Test
    fun aStaleLastKnownLocationIsUsedAfterTheShortTimeout() {
        grantAll()
        shadowOf(lm).setLastKnownLocation(
            LocationManager.GPS_PROVIDER, gps(48.85, 2.35, 40f).apply { time = System.currentTimeMillis() - 3_600_000 },
        )
        val c = launch(Mood.GOOD)
        c.get().pendingFile = fakeSelfie()
        compose.onNodeWithText("Save moment").assertIsNotEnabled()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(9))
        compose.onNodeWithText("Save moment").assertIsEnabled().performClick()
        waitFor { db.count() == 1 }
        val m = db.between(0, Long.MAX_VALUE).single()
        assertTrue(m.approximate)
        assertEquals(48.85, m.latitude, 1e-9)
    }

    @Test
    fun approximateOnlyLocationPermissionIsAccepted() {
        shadowOf(app).grantPermissions(
            Manifest.permission.CAMERA, Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        val c = launch(Mood.GOOD)
        compose.onNodeWithText("Allow access").assertDoesNotExist()
        compose.onNodeWithText("📍 Finding your location…").assertExists()
        c.pause().stop().destroy()
    }

    @Test
    fun noLocationEverMeansNoSaveAndARetryOffer() {
        grantAll()
        val c = launch(Mood.GOOD)
        c.get().pendingFile = fakeSelfie()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(9))
        compose.onNodeWithText("Retry").assertExists()
        compose.onNodeWithText("Save moment").assertIsNotEnabled()
        assertEquals(0, db.count())
        c.pause().stop().destroy()
    }

    @Test
    fun cancelSavesNothingAndDeletesTheTemporaryPhoto() {
        grantAll()
        val c = launch(Mood.ANGRY)
        val pending = fakeSelfie().also { c.get().pendingFile = it }
        shadowOf(lm).simulateLocation(gps(1.0, 2.0, 5f))
        compose.onNodeWithText("✕  Cancel").performClick()
        assertTrue(c.get().isFinishing)
        c.pause().stop().destroy()
        assertEquals(0, db.count())
        assertFalse("temp photo must be cleaned up", pending.exists())
    }

    @Test
    fun retakeDiscardsThePhotoButKeepsTheLocation() {
        grantAll()
        val c = launch(Mood.GOOD)
        val pending = fakeSelfie().also { c.get().pendingFile = it }
        shadowOf(lm).simulateLocation(gps(1.0, 2.0, 5f))
        compose.onNodeWithText("Retake").performClick()
        assertNull(c.get().pendingFile)
        assertFalse(pending.exists())
        compose.onNodeWithText("📍 Location locked (±5 m)").assertExists()
        c.pause().stop().destroy()
    }

    @Test
    fun aSecondEmojiTapStartsAFreshMomentInsteadOfReusingStaleState() {
        grantAll()
        val c = launch(Mood.BAD)
        val stale = fakeSelfie().also { c.get().pendingFile = it }
        shadowOf(lm).simulateLocation(gps(1.0, 2.0, 5f))
        compose.onNodeWithText("😞  Bad").assertExists()

        c.newIntent(intentFor(Mood.LOVE))

        compose.onNodeWithText("🥰  Love").assertExists()
        assertNull("old selfie must not carry over", c.get().pendingFile)
        assertFalse(stale.exists())
        // The tracker restarted: the old *exact* fix is gone; the position found seconds ago is
        // re-offered only as a rough last-known one.
        compose.onNodeWithText("📍 Location locked (±5 m)").assertDoesNotExist()
        compose.onNodeWithText("📍 Rough location found").assertExists()
        c.pause().stop().destroy()
    }

    @Test
    fun everyMoodOpensTheCaptureScreenShowingThatMood() {
        grantAll()
        for (mood in Mood.entries) {
            val c = launch(mood)
            compose.onNodeWithText("${mood.emoji}  ${mood.label}").assertExists()
            c.pause().stop().destroy()
        }
    }

    @Test
    fun anIntentWithoutAValidMoodClosesTheScreen() {
        grantAll()
        val bad = Intent(app, CaptureActivity::class.java).putExtra(CaptureActivity.EXTRA_MOOD, 99)
        val c = Robolectric.buildActivity(CaptureActivity::class.java, bad).setup()
        assertTrue(c.get().isFinishing)
        assertEquals(0, db.count())
    }
}
