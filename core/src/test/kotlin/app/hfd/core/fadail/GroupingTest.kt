package app.hfd.core.fadail

import app.hfd.core.Assets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class GroupingTest {
    private val all = Assets.fadail.fadail

    @Test
    fun weakOnlyWhenAskedAndInTheirOwnGroup() {
        val normal = FadailGrouping.group(all, showWeak = false)
        assertTrue(normal.none { it.first == FadailGroup.Weak })
        assertTrue(normal.flatMap { it.second }.all { it.grading.grade.acceptable && it.verified })

        val withWeak = FadailGrouping.group(all, showWeak = true)
        val weak = withWeak.single { it.first == FadailGroup.Weak }.second
        assertTrue(weak.isNotEmpty())
        assertTrue(weak.none { it.grading.grade.acceptable })
        // Weak entries never leak into the regular groups.
        assertTrue(withWeak.filter { it.first != FadailGroup.Weak }.flatMap { it.second }.all { it.grading.grade.acceptable })
    }

    @Test
    fun entriesAppearUnderEachOfTheirOccasions() {
        val groups = FadailGrouping.group(all, showWeak = false).toMap()
        val morning = groups[FadailGroup.ByOccasion(Occasion.MORNING)].orEmpty().map { it.id }
        val evening = groups[FadailGroup.ByOccasion(Occasion.EVENING)].orEmpty().map { it.id }
        assertTrue("three-quls-morning-evening" in morning)
        assertTrue("three-quls-morning-evening" in evening)
        assertTrue(groups[FadailGroup.ByOccasion(Occasion.FRIDAY)].orEmpty().any { it.id == "kahf-friday" })
        assertTrue(groups[FadailGroup.Long].orEmpty().any { it.id == "baqara-house" })
        assertTrue(groups[FadailGroup.ByOccasion(Occasion.ANY)].orEmpty().none { it.id == "baqara-house" })
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
