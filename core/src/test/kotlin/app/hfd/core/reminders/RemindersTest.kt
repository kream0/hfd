package app.hfd.core.reminders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class RemindersTest {
    private val paris = ZoneId.of("Europe/Paris")

    @Test
    fun nothingWhenOff() {
        assertTrue(ReminderPlanner.upcoming(ReminderConfig(), LocalDateTime.of(2026, 9, 24, 12, 0).atZone(paris)).isEmpty())
    }

    @Test
    fun reviewsDailyAtTheirTime() {
        val now = LocalDateTime.of(2026, 9, 24, 12, 0).atZone(paris)
        val list = ReminderPlanner.upcoming(ReminderConfig(reviews = true, reviewsAt = LocalTime.of(19, 0)), now)
        assertEquals(2, list.size)
        assertTrue(list.all { it.kind == ReminderKind.DUE_REVIEWS && it.at.toLocalTime() == LocalTime.of(19, 0) })
        assertEquals(list.sortedBy { it.at }, list)
        assertEquals(list.size, list.map { it.key }.toSet().size)
    }
}
