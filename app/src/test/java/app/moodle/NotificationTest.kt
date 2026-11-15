package app.moodle

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
class NotificationTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val nm get() = app.getSystemService(NotificationManager::class.java)

    private val slotIds = intArrayOf(
        R.id.slot1, R.id.slot2, R.id.slot3, R.id.slot4, R.id.slot5, R.id.slot6,
        R.id.slot7, R.id.slot8, R.id.slot9, R.id.slot10, R.id.slot11, R.id.slot12,
    )

    @Before fun grant() = shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

    @Test
    fun postsAPersistentSilentNotificationThatSurvivesASwipe() {
        assertTrue(MoodNotification.post(app))
        val n = shadowOf(nm).getNotification(MoodNotification.NOTIFICATION_ID)
        assertNotNull(n)
        assertTrue("must be ongoing", n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertFalse("must not auto-cancel", n.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertNotNull("swipe-away must re-post it", n.deleteIntent)
        assertNotNull(n.contentIntent)
        val channel = nm.getNotificationChannel(MoodNotification.CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertNull(channel.sound)
    }

    @Test
    fun doesNotPostWithoutTheNotificationPermission() {
        // The runtime notification permission only exists from Android 13.
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(NotificationStatus.PERMISSION_DENIED, MoodNotification.status(app))
        assertFalse(MoodNotification.post(app))
        assertEquals(0, shadowOf(nm).size())
    }

    @Test
    fun rePostingReplacesInsteadOfDuplicating() {
        MoodNotification.post(app)
        MoodNotification.post(app)
        assertEquals(1, shadowOf(nm).size())
    }

    @Test
    fun collapsedViewHasTheQuickRowInOrder() {
        val root = MoodNotification.buildViews(app, expanded = false).apply(app, FrameLayout(app))
        Mood.inRow(1).forEachIndexed { i, mood ->
            val tv = root.findViewById<TextView>(slotIds[i])
            assertEquals(mood.emoji, tv.text.toString())
            assertEquals(mood.label, tv.contentDescription.toString())
            assertTrue("${mood.label} must be tappable", tv.hasOnClickListeners())
        }
        // The second row is only in the expanded layout.
        for (i in 6 until 12) assertNull(root.findViewById<TextView>(slotIds[i]))
    }

    @Test
    fun expandedViewHasBothRowsInOrder() {
        val root = MoodNotification.buildViews(app, expanded = true).apply(app, FrameLayout(app))
        Mood.entries.forEachIndexed { i, mood ->
            val tv = root.findViewById<TextView>(slotIds[i])
            assertEquals(mood.emoji, tv.text.toString())
            assertEquals(mood.label, tv.contentDescription.toString())
            assertTrue("${mood.label} must be tappable", tv.hasOnClickListeners())
        }
    }

    /** Regression guard: PendingIntent identity ignores extras, so these must differ another way. */
    @Test
    fun everyMoodHasItsOwnPendingIntentCarryingItsOwnId() {
        val pending = Mood.entries.map { MoodNotification.capturePendingIntent(app, it) }
        for (i in pending.indices) for (j in i + 1 until pending.size) {
            assertNotEquals("buttons ${i + 1} and ${j + 1} collapsed into one", pending[i], pending[j])
        }
        Mood.entries.forEachIndexed { i, mood ->
            val sp = shadowOf(pending[i])
            assertTrue(sp.isActivityIntent)
            assertEquals(CaptureActivity::class.java.name, sp.savedIntent.component?.className)
            assertEquals(mood.id, sp.savedIntent.getIntExtra(CaptureActivity.EXTRA_MOOD, -1))
            assertEquals(mood.id, sp.requestCode)
        }
    }

    @Test
    fun tappingAnyEmojiInTheExpandedNotificationStartsCaptureForThatMood() {
        val root = MoodNotification.buildViews(app, expanded = true).apply(app, FrameLayout(app))
        Mood.entries.forEachIndexed { i, mood ->
            root.findViewById<TextView>(slotIds[i]).performClick()
            val started = shadowOf(app).nextStartedActivity
            assertNotNull("tapping ${mood.label} started nothing", started)
            assertEquals(CaptureActivity::class.java.name, started.component?.className)
            assertEquals(mood.id, started.getIntExtra(CaptureActivity.EXTRA_MOOD, -1))
        }
    }

    @Test
    fun tappingAnyEmojiInTheCollapsedNotificationStartsCaptureForThatMood() {
        val root = MoodNotification.buildViews(app, expanded = false).apply(app, FrameLayout(app))
        Mood.inRow(1).forEachIndexed { i, mood ->
            root.findViewById<TextView>(slotIds[i]).performClick()
            val started = shadowOf(app).nextStartedActivity
            assertNotNull("tapping ${mood.label} started nothing", started)
            assertEquals(mood.id, started.getIntExtra(CaptureActivity.EXTRA_MOOD, -1))
        }
    }
}
