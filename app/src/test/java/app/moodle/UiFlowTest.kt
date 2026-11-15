package app.moodle

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class UiFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val zone = ZoneId.systemDefault()

    @Before fun setUp() = MoodDb.reset()
    @After fun tearDown() = MoodDb.reset()

    private fun insertToday(hour: Int, mood: Mood, photo: String) {
        val at = LocalDate.now(zone).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
        MoodDb.get(app).insert(NewMoment(at, zone.id, mood, photo, 52.52, 13.405, 9f, false))
    }

    @Test
    fun emptyStateExplainsHowToStart() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("tap an emoji in the Moodle", substring = true, ignoreCase = true)
                .assertExists()
        }
    }

    @Test
    fun missingPermissionsAreCalledOutOnTheMainScreen() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("Setup needed").assertExists()
            compose.onNodeWithText("Camera permission is needed", substring = true).assertExists()
            compose.onNodeWithText("Location permission is needed", substring = true).assertExists()
        }
    }

    @Test
    fun calendarShowsEveryEmojiOfTheDayThenDayDetailThenDelete() {
        // A real (tiny) selfie for one of them, a missing file for the others: both must render.
        val real = Photos.file(app, "real.jpg")
        android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888)
            .compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, real.outputStream())
        insertToday(8, Mood.BAD, "real.jpg")
        insertToday(12, Mood.EXCITED, "missing1.jpg")
        insertToday(20, Mood.LOVE, "missing2.jpg")

        ActivityScenario.launch(MainActivity::class.java).use {
            // All three emojis of today are visible in one calendar cell.
            compose.onNodeWithText("😞").assertExists()
            compose.onNodeWithText("🤩").assertExists()
            compose.onNodeWithText("🥰").assertExists()

            // Tap the day -> its detail list.
            compose.onNodeWithText("🥰").performClick()
            compose.onNodeWithText("‹ Back").assertExists()
            compose.onNodeWithText("😞  Bad").assertExists()
            compose.onNodeWithText("🤩  Excited").assertExists()
            compose.onNodeWithText("🥰  Love").assertExists()
            compose.onAllNodesWithText("Delete").assertCountEquals(3)
            compose.onAllNodesWithText("📍 52.52000, 13.40500 (±9 m)").assertCountEquals(3)

            // Delete one: confirm dialog first, nothing happens until confirmed.
            compose.onAllNodesWithText("Delete")[0].performClick()
            compose.onNodeWithText("Delete this moment?").assertExists()
            assertEquals(3, MoodDb.get(app).count())
            compose.onNode(hasText("Delete") and hasAnyAncestor(isDialog())).performClick()
            compose.waitForIdle()
            assertEquals(2, MoodDb.get(app).count())
            assertEquals(false, real.exists()) // BAD was the earliest, its photo file went too

            compose.onNodeWithText("😞  Bad").assertDoesNotExist()
            compose.onNodeWithText("‹ Back").performClick()
            compose.onNodeWithText("😞").assertDoesNotExist()
            compose.onNodeWithText("🥰").assertExists()
        }
    }

    @Test
    fun cancellingTheDeleteDialogKeepsTheMoment() {
        insertToday(9, Mood.GOOD, "x.jpg")
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("🙂").performClick()
            compose.onNodeWithText("Delete").performClick()
            compose.onNodeWithText("Cancel").performClick()
            compose.waitForIdle()
            assertEquals(1, MoodDb.get(app).count())
            compose.onNodeWithText("🙂  Good").assertExists()
        }
    }

    @Test
    fun busyDaysAreCappedWithAPlusNIndicator() {
        repeat(12) { insertToday(8 + (it % 12), Mood.entries[it % 5], "p$it.jpg") }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("+4").assertExists() // 12 moments, 8 shown
        }
    }

}
