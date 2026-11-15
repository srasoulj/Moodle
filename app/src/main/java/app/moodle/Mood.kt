package app.moodle

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Every mood you can log, declared in display order: [row] 1 is the quick scale shown in the
 * collapsed notification (bad -> good), [row] 2 holds the extra feelings shown once the
 * notification is expanded.
 *
 * [id] is what gets stored in the database and carried in the notification's intents, so it is
 * permanent: **never renumber or reuse an id**, only append new ones. 1-5 are the original
 * five moods and must keep their meaning for already-saved moments.
 *
 * Emojis are limited to Unicode/Emoji 11 or older so they render on Android 9 (our minSdk).
 */
enum class Mood(val id: Int, val emoji: String, val label: String, val row: Int) {
    // Row 1: the quick scale, worst to best
    ANGRY(1, "😡", "Angry", 1),
    BAD(2, "😞", "Bad", 1),
    MEH(6, "😐", "Meh", 1),
    GOOD(3, "🙂", "Good", 1),
    EXCITED(4, "🤩", "Excited", 1),
    LOVE(5, "🥰", "Love", 1),

    // Row 2: other feelings
    SAD(7, "😢", "Sad", 2),
    ANXIOUS(8, "😰", "Anxious", 2),
    TIRED(9, "😴", "Tired", 2),
    THOUGHTFUL(10, "🤔", "Thoughtful", 2),
    CALM(11, "😌", "Calm", 2),
    GRATEFUL(12, "🤗", "Grateful", 2);

    companion object {
        fun fromId(id: Int): Mood? = entries.firstOrNull { it.id == id }

        /** Moods of one notification row, left to right. */
        fun inRow(row: Int): List<Mood> = entries.filter { it.row == row }
    }
}

/** A recorded moment: mood + time + place + selfie, saved together or not at all. */
data class Moment(
    val id: Long,
    val epochMillis: Long,
    /** Zone the phone was in when the moment was logged, so days stay right while travelling. */
    val zoneId: String,
    val mood: Mood,
    /** File name inside [Photos.dir]. */
    val photoName: String,
    val latitude: Double,
    val longitude: Double,
    /** Horizontal accuracy in metres, or a negative number when unknown. */
    val accuracyMeters: Float,
    /** True when the position is a last-known / low-accuracy fallback rather than a fresh GPS fix. */
    val approximate: Boolean,
) {
    val zone: ZoneId
        get() = try {
            ZoneId.of(zoneId)
        } catch (_: Exception) {
            ZoneId.systemDefault()
        }

    val localDateTime: ZonedDateTime get() = Instant.ofEpochMilli(epochMillis).atZone(zone)
    val localDate: LocalDate get() = localDateTime.toLocalDate()
}

data class NewMoment(
    val epochMillis: Long,
    val zoneId: String,
    val mood: Mood,
    val photoName: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val approximate: Boolean,
)
