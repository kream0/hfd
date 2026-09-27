package app.hfd.core.reminders

import app.hfd.core.prayer.Prayer
import app.hfd.core.prayer.PrayerCalculator
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

@Serializable
enum class ReminderKind(val fadilaId: String?) {
    /** Āyat al-Kursī after each obligatory prayer. */
    @SerialName("kursi") KURSI_AFTER_PRAYER("kursi-after-prayer"),
    /** As-Sajda and al-Mulk before sleep. */
    @SerialName("mulk") MULK_BEFORE_SLEEP("sajda-mulk-sleep"),
    /** Al-Kahf on Friday. */
    @SerialName("kahf") KAHF_FRIDAY("kahf-friday"),
    /** Āyāt due for review. */
    @SerialName("reviews") DUE_REVIEWS(null),
}

data class ReminderConfig(
    val kursi: Boolean = false,
    /** Minutes after the prayer time (time to pray first). */
    val kursiDelayMin: Int = 15,
    val mulk: Boolean = false,
    val mulkAt: LocalTime = LocalTime.of(22, 30),
    val kahf: Boolean = false,
    val kahfAt: LocalTime = LocalTime.of(10, 0),
    val reviews: Boolean = false,
    val reviewsAt: LocalTime = LocalTime.of(19, 0),
)

data class Reminder(val kind: ReminderKind, val at: ZonedDateTime, val prayer: Prayer? = null) {
    /** Unique per occurrence (WorkManager work name). */
    val key: String get() = "${kind.name.lowercase()}-${at.toEpochSecond() / 60}"
}

object ReminderPlanner {
    /**
     * Reminders firing after [now] and within [horizonHours], earliest first. Āyat al-Kursī
     * needs [prayers] (a location); without it that reminder is skipped.
     */
    fun upcoming(
        config: ReminderConfig,
        now: ZonedDateTime,
        prayers: PrayerCalculator?,
        horizonHours: Long = 36,
    ): List<Reminder> {
        val zone: ZoneId = now.zone
        val end = now.plusHours(horizonHours)
        val out = mutableListOf<Reminder>()
        var date = now.toLocalDate().minusDays(1)
        while (!date.atStartOfDay(zone).isAfter(end)) {
            if (config.kursi && prayers != null) {
                val day = prayers.day(date, zone)
                for ((prayer, t) in day.times) out += Reminder(ReminderKind.KURSI_AFTER_PRAYER, t.plusMinutes(config.kursiDelayMin.toLong()), prayer)
            }
            if (config.mulk) out += Reminder(ReminderKind.MULK_BEFORE_SLEEP, date.atTime(config.mulkAt).atZone(zone))
            if (config.kahf && date.dayOfWeek == DayOfWeek.FRIDAY) out += Reminder(ReminderKind.KAHF_FRIDAY, date.atTime(config.kahfAt).atZone(zone))
            if (config.reviews) out += Reminder(ReminderKind.DUE_REVIEWS, date.atTime(config.reviewsAt).atZone(zone))
            date = date.plusDays(1)
        }
        return out.filter { it.at.isAfter(now) && !it.at.isAfter(end) }.sortedBy { it.at }
    }
}
