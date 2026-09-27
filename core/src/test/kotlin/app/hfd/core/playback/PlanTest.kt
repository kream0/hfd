package app.hfd.core.playback

import app.hfd.core.quran.AyahRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanTest {
    private fun ayat(sura: Int, range: IntRange) = range.map { AyahRef(sura, it) }

    /** Compact description of items: "B67:1", "A2:255x1", "G2:255x1". */
    private fun describe(items: Iterable<PlanItem>) = items.joinToString(" ") {
        when (it) {
            is PlanItem.Basmala -> "B${it.ref}"
            is PlanItem.Ayah -> "A${it.ref}x${it.rep}"
            is PlanItem.Gap -> "G${it.ref}x${it.cursor.rep}"
        }
    }

    @Test
    fun repeatsEachAyaWithGaps() {
        val spec = PlanSpec(ayat(2, 255..256), repeatEach = 2, gap = GapMode.ONE)
        assertEquals(
            "A2:255x1 G2:255x1 A2:255x2 G2:255x2 A2:256x1 G2:256x1 A2:256x2 G2:256x2",
            describe(PlaybackPlan.items(spec).toList()),
        )
    }

    @Test
    fun basmalaBeforeAyaOneExceptFatihaAndTawba() {
        assertEquals("B67:1 A67:1x1 A67:2x1", describe(PlaybackPlan.items(PlanSpec(ayat(67, 1..2))).toList()))
        assertEquals("A67:1x1 A67:2x1", describe(PlaybackPlan.items(PlanSpec(ayat(67, 1..2), basmala = false)).toList()))
        assertEquals("A1:1x1 A1:2x1", describe(PlaybackPlan.items(PlanSpec(ayat(1, 1..2))).toList()))
        assertEquals("A9:1x1", describe(PlaybackPlan.items(PlanSpec(ayat(9, 1..1))).toList()))
        // A sub-range that doesn't start at āya 1 has no basmala.
        assertEquals("A67:2x1", describe(PlaybackPlan.items(PlanSpec(ayat(67, 2..2))).toList()))
    }

    @Test
    fun basmalaOncePerPassEvenWithRepeats() {
        val spec = PlanSpec(listOf(AyahRef(112, 1)), repeatEach = 2, repeatRange = 2)
        assertEquals(
            "B112:1 A112:1x1 A112:1x2 B112:1 A112:1x1 A112:1x2",
            describe(PlaybackPlan.items(spec).toList()),
        )
    }

    @Test
    fun rangesSpanningSurasGetEachBasmala() {
        val spec = PlanSpec(ayat(113, 1..2) + ayat(114, 1..1))
        assertEquals("B113:1 A113:1x1 A113:2x1 B114:1 A114:1x1", describe(PlaybackPlan.items(spec).toList()))
    }

    @Test
    fun infiniteRepeatsAreLazy() {
        val spec = PlanSpec(ayat(2, 255..256), repeatEach = PlanSpec.INFINITE, gap = GapMode.HALF)
        val first = PlaybackPlan.items(spec).take(5).toList()
        assertEquals("A2:255x1 G2:255x1 A2:255x2 G2:255x2 A2:255x3", describe(first))
        assertTrue(first.filterIsInstance<PlanItem.Gap>().all { it.factor == 0.5f })
        val infiniteRange = PlanSpec(ayat(2, 255..255), repeatRange = PlanSpec.INFINITE)
        assertEquals(100, PlaybackPlan.items(infiniteRange).take(100).count())
    }

    @Test
    fun startsFromACursor() {
        val spec = PlanSpec(ayat(67, 1..3), repeatEach = 3)
        assertEquals("A67:2x2 A67:2x3 A67:3x1 A67:3x2 A67:3x3", describe(PlaybackPlan.items(spec, Cursor(1, 1, 2)).toList()))
        // Starting at āya 1 of a sūra includes its basmala.
        assertEquals("B67:1 A67:1x1", describe(PlaybackPlan.items(spec, Cursor(1, 0, 1)).take(2).toList()))
    }

    @Test
    fun windowsJoinSeamlessly() {
        val spec = PlanSpec(ayat(113, 1..5) + ayat(114, 1..6), repeatEach = 3, repeatRange = 2, gap = GapMode.ONE_HALF)
        val all = PlaybackPlan.items(spec).toList()
        val built = mutableListOf<PlanItem>()
        var window = PlaybackPlan.items(spec).take(7).toList()
        while (window.isNotEmpty()) {
            built += window
            window = PlaybackPlan.after(spec, window.last()).take(7).toList()
        }
        assertEquals(describe(all), describe(built))
        assertEquals(1 + 5 * 3 * 2 + 1 + 6 * 3 * 2, all.size / 2)
    }

    @Test
    fun mediaIdsRoundTrip() {
        val spec = PlanSpec(ayat(113, 1..5), repeatEach = 2, repeatRange = 2, gap = GapMode.ONE)
        for (item in PlaybackPlan.items(spec)) {
            assertEquals(item, PlaybackPlan.decode(spec, item.mediaId))
        }
        assertEquals("2:255:3#1", PlanItem.Ayah(Cursor(1, 0, 3), AyahRef(2, 255), 5).mediaId)
        val parsed = MediaIds.parse("2:255:3#1/gap")!!
        assertEquals(AyahRef(2, 255), parsed.ref)
        assertEquals(3, parsed.rep)
        assertEquals(MediaIds.Kind.GAP, parsed.kind)
        assertNull(MediaIds.parse("nonsense"))
        assertNull(PlaybackPlan.decode(spec, "2:255:1#1"))
    }

    @Test
    fun nextAndPreviousJumpWholeAyat() {
        val spec = PlanSpec(ayat(67, 1..3), repeatEach = 5, repeatRange = 2)
        assertEquals(Cursor(1, 2, 1), PlaybackPlan.nextAyah(spec, Cursor(1, 1, 4)))
        assertEquals(Cursor(2, 0, 1), PlaybackPlan.nextAyah(spec, Cursor(1, 2, 3)))
        assertNull(PlaybackPlan.nextAyah(spec, Cursor(2, 2, 1)))
        assertEquals(Cursor(1, 0, 1), PlaybackPlan.previousAyah(spec, Cursor(1, 1, 3)))
        assertEquals(Cursor(1, 2, 1), PlaybackPlan.previousAyah(spec, Cursor(2, 0, 2)))
        assertEquals(Cursor(1, 0, 1), PlaybackPlan.previousAyah(spec, Cursor(1, 0, 4)))
    }
}
