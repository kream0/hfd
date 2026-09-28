package app.hfd.core.reminders

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * What a reminder is for. Only reviews: the app is for learning, so the reminders to recite a
 * passage at its time (after prayer, before sleep, on Friday) were removed in 1.6.0.
 */
@Serializable
enum class ReminderKind {
    /** Āyāt due for review. */
    @SerialName("reviews") DUE_REVIEWS,
}

data class ReminderConfig(
    val reviews: Boolean = false,
    val reviewsAt: LocalTime = LocalTime.of(19, 0),
)

data class Reminder(val kind: ReminderKind, val at: ZonedDateTime) {
    /** Unique per occurrence (WorkManager work name). */
    val key: String get() = "${kind.name.lowercase()}-${at.toEpochSecond() / 60}"
}

object ReminderPlanner {
    /** Reminders firing after [now] and within [horizonHours], earliest first. */
    fun upcoming(config: ReminderConfig, now: ZonedDateTime, horizonHours: Long = 36): List<Reminder> {
        val zone: ZoneId = now.zone
        val end = now.plusHours(horizonHours)
        val out = mutableListOf<Reminder>()
        var date = now.toLocalDate().minusDays(1)
        while (!date.atStartOfDay(zone).isAfter(end)) {
            if (config.reviews) out += Reminder(ReminderKind.DUE_REVIEWS, date.atTime(config.reviewsAt).atZone(zone))
            date = date.plusDays(1)
        }
        return out.filter { it.at.isAfter(now) && !it.at.isAfter(end) }.sortedBy { it.at }
    }
}
