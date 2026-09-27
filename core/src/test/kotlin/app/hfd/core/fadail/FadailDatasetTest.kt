package app.hfd.core.fadail

import app.hfd.core.Assets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FadailDatasetTest {
    @Test
    fun datasetIsValid() {
        val errors = FadailValidator.validate(Assets.fadail, Assets.suras)
        assertTrue(errors.joinToString("\n"), errors.isEmpty())
    }

    @Test
    fun everyRangeFitsItsSura() {
        val counts = Assets.suras.associate { it.index to it.ayas }
        for (f in Assets.fadail.fadail) for (r in f.ranges) {
            val max = counts.getValue(r.sura)
            assertTrue("${f.id} $r", r.from in 1..max && r.to in r.from..max)
        }
    }

    @Test
    fun everyEntryHasASource() {
        Assets.fadail.fadail.forEach { assertTrue(it.id, it.sources.isNotEmpty()) }
    }

    @Test
    fun weakAndUnverifiedEntriesAreHiddenByDefault() {
        for (f in Assets.fadail.fadail) {
            val shouldShow = f.verified && f.grading.grade.acceptable
            assertEquals(f.id, shouldShow, f.visible(showWeak = false))
            if (!f.verified) assertTrue(f.id, !f.visible(showWeak = true))
        }
    }

    @Test
    fun wholeSuraEntriesCoverTheWholeSura() {
        val counts = Assets.suras.associate { it.index to it.ayas }
        // Entries tagged "sura" are about complete sūras: their ranges must span them.
        for (f in Assets.fadail.fadail.filter { "sura" in it.tags }) for (r in f.ranges) {
            assertEquals("${f.id} $r", 1, r.from)
            assertEquals("${f.id} $r", counts.getValue(r.sura), r.to)
        }
    }
}
