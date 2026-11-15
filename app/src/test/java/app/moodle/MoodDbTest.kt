package app.moodle

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MoodDbTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before fun setUp() = MoodDb.reset()
    @After fun tearDown() = MoodDb.reset()

    private fun new(ts: Long, mood: Mood, photo: String, approx: Boolean = false) =
        NewMoment(ts, "Europe/Berlin", mood, photo, 52.52, 13.405, 8.5f, approx)

    @Test
    fun roundTripsEveryField() {
        val db = MoodDb.get(app)
        db.insert(new(1_000, Mood.LOVE, "a.jpg", approx = true))
        val m = db.between(0, 10_000).single()
        assertEquals(Mood.LOVE, m.mood)
        assertEquals(1_000L, m.epochMillis)
        assertEquals("Europe/Berlin", m.zoneId)
        assertEquals("a.jpg", m.photoName)
        assertEquals(52.52, m.latitude, 1e-9)
        assertEquals(13.405, m.longitude, 1e-9)
        assertEquals(8.5f, m.accuracyMeters, 0f)
        assertTrue(m.approximate)
    }

    @Test
    fun rangeQueryIsHalfOpenAndOrdered() {
        val db = MoodDb.get(app)
        db.insert(new(3_000, Mood.GOOD, "c.jpg"))
        db.insert(new(1_000, Mood.BAD, "a.jpg"))
        db.insert(new(2_000, Mood.EXCITED, "b.jpg"))
        assertEquals(listOf("a.jpg", "b.jpg", "c.jpg"), db.between(0, 10_000).map { it.photoName })
        assertEquals(listOf("b.jpg"), db.between(2_000, 3_000).map { it.photoName }) // [from, to)
        assertEquals(3, db.count())
    }

    @Test
    fun deleteReturnsThePhotoNameAndRemovesTheRow() {
        val db = MoodDb.get(app)
        val id = db.insert(new(1_000, Mood.ANGRY, "gone.jpg"))
        db.insert(new(2_000, Mood.GOOD, "stay.jpg"))
        assertEquals("gone.jpg", db.delete(id))
        assertEquals(listOf("stay.jpg"), db.between(0, 10_000).map { it.photoName })
        assertNull(db.delete(id))
    }

    @Test
    fun photosAreCommittedOutOfTheCacheIntoPrivateStorage() {
        val pending = Photos.newPendingFile(app).apply { writeText("jpeg-bytes") }
        val name = Photos.commit(app, pending, 12345L)
        assertFalse(pending.exists())
        assertTrue(Photos.file(app, name).exists())
        assertTrue(Photos.file(app, name).absolutePath.startsWith(app.filesDir.absolutePath))
        Photos.delete(app, name)
        assertFalse(Photos.file(app, name).exists())
    }
}
