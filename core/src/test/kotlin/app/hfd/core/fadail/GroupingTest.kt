package app.hfd.core.fadail

import app.hfd.core.Assets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class GroupingTest {
    private val all = Assets.fadail.fadail

    @Test
    fun suggestionsComeFromSoundNarrations() {
        val sleep = FadailGrouping.suggested(all, listOf(Occasion.BEFORE_SLEEP), showWeak = false).map { it.id }
        assertTrue("kursi" in sleep)
        assertTrue("mulk" in sleep)
        assertTrue("hadid" in sleep)
        // Al-Wāqiʿa at night rests on a weak narration: only with the setting.
        val night = { weak: Boolean -> FadailGrouping.suggested(all, listOf(Occasion.NIGHT), weak).map { it.id } }
        assertTrue("waqia" !in night(false))
        assertTrue("waqia" in night(true))
        assertTrue(FadailGrouping.suggested(all, listOf(Occasion.FRIDAY), showWeak = false).any { it.id == "kahf" })
    }

    @Test
    fun suggestionsKeepTheReferenceOrder() {
        val ids = all.map { it.id }
        val morning = FadailGrouping.suggested(all, listOf(Occasion.MORNING, Occasion.EVENING), showWeak = false).map { it.id }
        assertEquals(morning.sortedBy { ids.indexOf(it) }, morning)
    }

    @Test
    fun suggestionsFollowTheClock() {
        // 2026-09-25 is a Friday.
        assertEquals(listOf(Occasion.FRIDAY, Occasion.MORNING), FadailGrouping.now(LocalDateTime.of(2026, 9, 25, 7, 0)))
        assertEquals(listOf(Occasion.EVENING), FadailGrouping.now(LocalDateTime.of(2026, 9, 27, 17, 30)))
        assertEquals(listOf(Occasion.NIGHT, Occasion.BEFORE_SLEEP), FadailGrouping.now(LocalDateTime.of(2026, 9, 27, 23, 0)))
        assertEquals(emptyList<Occasion>(), FadailGrouping.now(LocalDateTime.of(2026, 9, 27, 13, 0)))
    }
}
