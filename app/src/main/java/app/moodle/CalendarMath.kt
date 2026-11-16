package app.moodle

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Pure calendar helpers (no Android types) so they can be unit-tested on the JVM. */
object CalendarMath {

    /**
     * Cells for a month grid, row by row (7 per row). `null` cells are padding before the 1st
     * and after the last day of the month.
     */
    fun gridDays(month: YearMonth, firstDayOfWeek: DayOfWeek): List<LocalDate?> {
        val lead = (month.atDay(1).dayOfWeek.value - firstDayOfWeek.value + 7) % 7
        val cells = ArrayList<LocalDate?>()
        repeat(lead) { cells.add(null) }
        for (d in 1..month.lengthOfMonth()) cells.add(month.atDay(d))
        while (cells.size % 7 != 0) cells.add(null)
        return cells
    }

    fun weekdayOrder(firstDayOfWeek: DayOfWeek): List<DayOfWeek> =
        List(7) { firstDayOfWeek.plus(it.toLong()) }

    /**
     * Epoch-millis range [from, to) that is guaranteed to contain every moment whose *own*
     * local date falls in [month]. It is padded by two days because a moment is bucketed by the
     * zone it was recorded in, which can differ from the phone's current zone by up to 26 h.
     */
    fun queryRange(month: YearMonth, zone: ZoneId): Pair<Long, Long> {
        val from = month.atDay(1).minusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = month.plusMonths(1).atDay(1).plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
        return from to to
    }

    /** Groups moments by the local date they were recorded on, each day oldest first. */
    fun byDay(moments: List<Moment>): Map<LocalDate, List<Moment>> =
        moments.sortedBy { it.epochMillis }.groupBy { it.localDate }
}
