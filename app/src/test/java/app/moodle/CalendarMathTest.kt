package app.moodle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

class CalendarMathTest {

    private fun moment(id: Long, at: ZonedDateTime, mood: Mood = Mood.GOOD) = Moment(
        id = id,
        epochMillis = at.toInstant().toEpochMilli(),
        zoneId = at.zone.id,
        mood = mood,
        photoName = "p$id.jpg",
        latitude = 1.0,
        longitude = 2.0,
        accuracyMeters = 5f,
        approximate = false,
    )

    @Test
    fun gridStartsOnTheRightWeekdayMondayFirst() {
        // 1 Nov 2026 is a Sunday -> with Monday first there are 6 leading blanks.
        val cells = CalendarMath.gridDays(YearMonth.of(2026, 11), DayOfWeek.MONDAY)
        assertEquals(6, cells.indexOfFirst { it != null })
        assertEquals(LocalDate.of(2026, 11, 1), cells[6])
        assertEquals(0, cells.size % 7)
        assertEquals(30, cells.count { it != null })
        assertEquals(LocalDate.of(2026, 11, 30), cells.filterNotNull().last())
    }

    @Test
    fun gridStartsOnTheRightWeekdaySundayFirst() {
        val cells = CalendarMath.gridDays(YearMonth.of(2026, 11), DayOfWeek.SUNDAY)
        assertEquals(LocalDate.of(2026, 11, 1), cells[0])
    }

    @Test
    fun monthThatFillsExactlyFourRowsHasNoPadding() {
        // Feb 2021 starts on a Monday and has 28 days.
        val cells = CalendarMath.gridDays(YearMonth.of(2021, 2), DayOfWeek.MONDAY)
        assertEquals(28, cells.size)
        assertTrue(cells.all { it != null })
    }

    @Test
    fun weekdayOrderRotates() {
        assertEquals(DayOfWeek.MONDAY, CalendarMath.weekdayOrder(DayOfWeek.MONDAY).first())
        assertEquals(DayOfWeek.SUNDAY, CalendarMath.weekdayOrder(DayOfWeek.MONDAY).last())
        assertEquals(DayOfWeek.SATURDAY, CalendarMath.weekdayOrder(DayOfWeek.SATURDAY).first())
    }

    @Test
    fun momentsAreBucketedByTheZoneTheyWereRecordedIn() {
        // 23:30 in Los Angeles on 10 Nov is 07:30 UTC on 11 Nov. It belongs to the 10th.
        val la = ZonedDateTime.of(2026, 11, 10, 23, 30, 0, 0, ZoneId.of("America/Los_Angeles"))
        val tokyo = ZonedDateTime.of(2026, 11, 11, 8, 0, 0, 0, ZoneId.of("Asia/Tokyo"))
        val byDay = CalendarMath.byDay(listOf(moment(2, tokyo), moment(1, la)))
        assertEquals(listOf(1L), byDay[LocalDate.of(2026, 11, 10)]?.map { it.id })
        assertEquals(listOf(2L), byDay[LocalDate.of(2026, 11, 11)]?.map { it.id })
    }

    @Test
    fun eachDayIsOldestFirst() {
        val zone = ZoneId.of("Europe/Berlin")
        val morning = moment(1, ZonedDateTime.of(2026, 11, 5, 8, 0, 0, 0, zone), Mood.BAD)
        val evening = moment(2, ZonedDateTime.of(2026, 11, 5, 20, 0, 0, 0, zone), Mood.LOVE)
        val day = CalendarMath.byDay(listOf(evening, morning))[LocalDate.of(2026, 11, 5)]!!
        assertEquals(listOf(Mood.BAD, Mood.LOVE), day.map { it.mood })
    }

    @Test
    fun queryRangeCoversMomentsFromAnyZoneOnTheMonthEdges() {
        val device = ZoneId.of("Pacific/Auckland") // UTC+13
        val (from, to) = CalendarMath.queryRange(YearMonth.of(2026, 11), device)
        // Recorded in Honolulu (UTC-10) at 23:59 on 30 Nov: local date is still 30 Nov.
        val edgeLate = ZonedDateTime.of(2026, 11, 30, 23, 59, 0, 0, ZoneId.of("Pacific/Honolulu"))
        // Recorded in Kiritimati (UTC+14) at 00:01 on 1 Nov.
        val edgeEarly = ZonedDateTime.of(2026, 11, 1, 0, 1, 0, 0, ZoneId.of("Pacific/Kiritimati"))
        for (m in listOf(edgeLate, edgeEarly)) {
            val ms = m.toInstant().toEpochMilli()
            assertTrue("$m should be inside the query range", ms in from until to)
        }
    }

    @Test
    fun moodScoresAreOrderedAndUnique() {
        assertEquals(listOf(1, 2, 3, 4, 5), Mood.entries.map { it.score })
        assertEquals(5, Mood.entries.map { it.emoji }.toSet().size)
        assertEquals(Mood.LOVE, Mood.fromScore(5))
        assertNull(Mood.fromScore(0))
        assertNull(Mood.fromScore(6))
    }
}
