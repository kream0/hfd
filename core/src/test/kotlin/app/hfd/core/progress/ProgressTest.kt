package app.hfd.core.progress

import app.hfd.core.fadail.AyahRange
import app.hfd.core.fadail.Fadila
import app.hfd.core.fadail.Grade
import app.hfd.core.fadail.Grading
import app.hfd.core.fadail.Localized
import app.hfd.core.fadail.Occasion
import app.hfd.core.quran.AyahRef
import app.hfd.core.srs.CardState
import app.hfd.core.srs.Fsrs
import app.hfd.core.srs.Rating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ProgressTest {
    private val zone: ZoneId = ZoneId.of("Europe/Paris")
    private val fsrs = Fsrs()
    private fun at(day: Int, hour: Int = 9, minute: Int = 0): Long =
        LocalDate.of(2026, 3, day).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private val log = listOf(
        Event.Listen(at(1), "2:255", 50_000, 1, "alafasy"),
        Event.Listen(at(1, 9, 5), "2:255", 104_000, 2),
        Event.Rate(at(1, 10), "2:255", Rating.GOOD, Mode.LEARN, 20_000),
        Event.Rate(at(1, 10, 10), "2:255", Rating.GOOD, Mode.LEARN, 15_000),
        Event.Rate(at(1, 10, 20), "112:1", Rating.AGAIN, Mode.LEARN, 5_000),
        Event.Chain(at(1, 10, 30), listOf("112:1", "112:2"), ok = true, ms = 30_000),
        Event.Rate(at(3, 8), "2:255", Rating.GOOD, Mode.REVIEW, 10_000),
        Event.Test(at(3, 9), "kursi-greatest", 1, 1),
    )

    @Test
    fun replayEqualsIncrementalApply() {
        val replayed = ProgressBook.replay(log.asSequence(), fsrs, zone)
        val book = ProgressBook(fsrs = fsrs, zone = zone)
        var state = ProgressState()
        for (e in log) {
            val b = ProgressBook(state, fsrs, zone)
            b.apply(e)
            state = b.snapshot()
            book.apply(e)
        }
        assertEquals(replayed, state)
        assertEquals(replayed, book.snapshot())
        assertEquals(log.size, replayed.events)
    }

    @Test
    fun listeningAndRatingsAddUp() {
        val s = ProgressBook.replay(log.asSequence(), fsrs, zone)
        val kursi = s["2:255"]
        assertEquals(3, kursi.listens)
        assertEquals(154_000, kursi.listenMs)
        assertEquals(at(1, 9, 5), kursi.lastHeard)
        assertEquals(3, kursi.card.reps)
        assertTrue(kursi.memorised)
        assertEquals(CardState.LEARNING, s["112:1"].card.state)
        assertEquals(1, s["112:2"].chains)
        assertEquals(1, s["112:2"].chainsOk)
        val day1 = s.days.getValue(LocalDate.of(2026, 3, 1).toEpochDay())
        assertEquals(154_000, day1.listenMs)
        assertEquals(3, day1.ratings)
        assertEquals(2, day1.learned) // 2:255 and 112:1 were rated for the first time
        assertEquals(20_000 + 15_000 + 5_000 + 30_000, day1.reciteMs.toInt())
        assertEquals(at(3, 9), s.tests["kursi-greatest"])
        assertEquals(154_000, Stats.totalListenMs(s))
        assertEquals(1, Stats.memorisedCount(s))
    }

    @Test
    fun eventsRoundTripAndBadLinesAreSkipped() {
        for (e in log) assertEquals(e, EventLog.decode(EventLog.encode(e)))
        assertTrue(EventLog.encode(log[0]).startsWith("{\"t\":\"listen\""))
        assertNull(EventLog.decode("{\"t\":\"future-kind\",\"at\":1}"))
        assertNull(EventLog.decode("not json"))
        assertNull(EventLog.decode(""))
    }

    @Test
    fun streakCountsDaysMeetingTheGoal() {
        val goal = 10 * 60_000L
        fun day(d: Int) = LocalDate.of(2026, 3, d).toEpochDay()
        val met = DayStats(listenMs = goal)
        val days = mapOf(day(1) to met, day(2) to met, day(4) to met, day(5) to met, day(6) to DayStats(listenMs = goal - 1))
        // Today (6th) not met yet: the streak still stands on the 4th–5th.
        assertEquals(Streak(2, 2, false), Stats.streak(days, goal, day(6)))
        assertEquals(Streak(3, 3, true), Stats.streak(days + (day(6) to met), goal, day(6)))
        // A missed day breaks it.
        assertEquals(Streak(0, 2, false), Stats.streak(days, goal, day(8)))
        assertEquals(Streak(0, 0, false), Stats.streak(emptyMap(), goal, day(8)))
    }

    @Test
    fun dueQueueIsTodaysStartedAyatInOrder() {
        val s = ProgressBook.replay(log.asSequence(), fsrs, zone)
        // On the 1st, 112:1 (relearn in minutes) is due today; 2:255 is not.
        assertEquals(listOf(AyahRef(112, 1)), Stats.due(s, at(1, 11), zone))
        // Much later everything started is due, in muṣḥaf order.
        assertEquals(listOf(AyahRef(2, 255), AyahRef(112, 1)), Stats.due(s, at(1) + 400 * Fsrs.DAY_MS, zone))
        assertEquals(listOf(AyahRef(2, 255)), Stats.due(s, at(1) + 400 * Fsrs.DAY_MS, zone, within = setOf("2:255")))
    }

    @Test
    fun fadilaProgressAndTests() {
        val s = ProgressBook.replay(log.asSequence(), fsrs, zone)
        val f = Fadila(
            id = "kursi-greatest",
            title = Localized("آية", "Āyat al-Kursī", "Āyat al-Kursī"),
            ranges = listOf(AyahRange(2, 255, 255)),
            virtue = Localized(null, "x", "x"),
            sources = emptyList(),
            grading = Grading(Grade.SAHIH, "Muslim"),
            occasions = listOf(Occasion.ANY),
            verified = true,
        )
        val p = Stats.fadila(s, f, fsrs, at(3, 10))
        assertEquals(1, p.memorised)
        assertEquals(1, p.total)
        assertEquals(1f, p.fraction)
        assertTrue(p.strength > 0.8f)
        assertFalse(Stats.testSuggested(s, f, at(4)))
        assertTrue(Stats.testSuggested(s, f, at(3) + 31 * Fsrs.DAY_MS))
    }
}
