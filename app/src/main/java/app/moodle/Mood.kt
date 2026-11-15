package app.moodle

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** The five moods, worst to best. [score] is what gets stored (1..5). */
enum class Mood(val score: Int, val emoji: String, val label: String) {
    ANGRY(1, "😡", "Angry"),
    BAD(2, "😞", "Bad"),
    GOOD(3, "🙂", "Good"),
    EXCITED(4, "🤩", "Excited"),
    LOVE(5, "🥰", "Love");

    companion object {
        fun fromScore(score: Int): Mood? = entries.firstOrNull { it.score == score }
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
