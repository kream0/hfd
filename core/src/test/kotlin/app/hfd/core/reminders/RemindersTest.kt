package app.hfd.core.reminders

import app.hfd.core.prayer.Prayer
import app.hfd.core.prayer.PrayerCalculator
import app.hfd.core.prayer.PrayerMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class RemindersTest {
    private val paris = ZoneId.of("Europe/Paris")
    private val calc = PrayerCalculator(48.8566, 2.3522, PrayerMethod.UOIF)

    @Test
    fun nothingWhenAllOff() {
        assertTrue(ReminderPlanner.upcoming(ReminderConfig(), LocalDateTime.of(2026, 9, 24, 12, 0).atZone(paris), calc).isEmpty())
    }

    @Test
    fun kursiFollowsEachPrayer() {
        val now = LocalDateTime.of(2026, 9, 24, 0, 0).atZone(paris)
        val list = ReminderPlanner.upcoming(ReminderConfig(kursi = true), now, calc, horizonHours = 24)
        assertEquals(5, list.size)
        val today = calc.day(now.toLocalDate(), paris).times
        for (r in list) assertEquals(today.getValue(r.prayer!!).plusMinutes(15), r.at)
        assertEquals(listOf(Prayer.FAJR, Prayer.DHUHR, Prayer.ASR, Prayer.MAGHRIB, Prayer.ISHA), list.map { it.prayer })
        // Without a location there is no prayer time to follow.
        assertTrue(ReminderPlanner.upcoming(ReminderConfig(kursi = true), now, null).isEmpty())
    }

    @Test
    fun kahfOnlyOnFridayAndDailyOnesDaily() {
        // Thursday noon: next 36 h hold one Friday.
        val now = LocalDateTime.of(2026, 9, 24, 12, 0).atZone(paris)
        val list = ReminderPlanner.upcoming(ReminderConfig(kahf = true, mulk = true, reviews = true), now, calc)
        val kahf = list.filter { it.kind == ReminderKind.KAHF_FRIDAY }
        assertEquals(1, kahf.size)
        assertEquals(DayOfWeek.FRIDAY, kahf[0].at.dayOfWeek)
        assertEquals(LocalTime.of(10, 0), kahf[0].at.toLocalTime())
        assertEquals(2, list.count { it.kind == ReminderKind.MULK_BEFORE_SLEEP })
        assertEquals(2, list.count { it.kind == ReminderKind.DUE_REVIEWS })
        assertEquals(list.sortedBy { it.at }, list)
        assertEquals(list.size, list.map { it.key }.toSet().size)
    }
}
