package app.hfd.core.quran

import app.hfd.core.Assets
import app.hfd.core.HfdJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PartsTest {
    private val parts = Parts(HfdJson.decodeFromString(PartsFile.serializer(), Assets.file("quran/parts.json").readText()))

    @Test
    fun theAjzaStartWhereTheMushafsDo() {
        val known = listOf(
            "1:1", "2:142", "2:253", "3:93", "4:24", "4:148", "5:82", "6:111", "7:88", "8:41", "9:93", "11:6", "12:53", "15:1", "17:1",
            "18:75", "21:1", "23:1", "25:21", "27:56", "29:46", "33:31", "36:28", "39:32", "41:47", "46:1", "51:31", "58:1", "67:1", "78:1",
        )
        assertEquals(known, parts.juz.map { it.key })
        assertEquals(60, parts.hizb.size)
        assertEquals(240, parts.quarters.size)
    }

    @Test
    fun everyPartIsAnAyaOfTheText() {
        val counts = Assets.suras.associate { it.index to it.ayas }
        assertTrue(parts.quarters.all { it.aya in 1..counts.getValue(it.sura) })
    }

    /**
     * Tanzil's text carries the quarter's sign (۞) at the head of each āya that opens one, but
     * for a sūra's first. The two agree everywhere but in al-Ḥijr, where the text marks 15:49
     * and the metadata says 15:50 (a quarter inside a ḥizb: no juzʾ nor ḥizb starts there).
     */
    @Test
    fun theTextsQuarterSignsAreTheQuarters() {
        val marked = Assets.suras.flatMap { s -> (1..s.ayas).map { AyahRef(s.index, it) } }
            .filter { Assets.quran.text(it)!!.startsWith("۞") }.toSet()
        val quarters = parts.quarters.toSet()
        assertEquals(setOf(AyahRef(15, 49)), marked - quarters)
        assertEquals(setOf(AyahRef(15, 50)), (quarters - marked).filter { it.aya != 1 }.toSet())
        assertTrue(AyahRef(15, 50) !in parts.hizb)
    }

    @Test
    fun anAyaIsInItsParts() {
        assertEquals(1, parts.juzOf(AyahRef(1, 1)))
        assertEquals(1, parts.juzOf(AyahRef(2, 141)))
        assertEquals(2, parts.juzOf(AyahRef(2, 142)))
        assertEquals(3, parts.juzOf(AyahRef(2, 255)))
        assertEquals(30, parts.juzOf(AyahRef(114, 6)))
        assertEquals(1, parts.hizbOf(AyahRef(2, 74)))
        assertEquals(2, parts.hizbOf(AyahRef(2, 75)))
        assertEquals(60, parts.hizbOf(AyahRef(114, 6)))
        assertEquals(2, parts.juzAt(AyahRef(2, 142)))
        assertEquals(3, parts.hizbAt(AyahRef(2, 142)))
        assertEquals(null, parts.juzAt(AyahRef(2, 143)))
        assertEquals(2, parts.hizbAt(AyahRef(2, 75)))
        assertEquals(null, parts.juzAt(AyahRef(2, 75)))
    }
}
