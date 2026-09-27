package app.hfd.core.quran

import app.hfd.core.Assets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuranDataTest {
    @Test
    fun metadataCoversTheWholeQuran() {
        val suras = Assets.suras
        assertEquals(114, suras.size)
        assertEquals((1..114).toList(), suras.map { it.index })
        assertEquals(Tanzil.TOTAL_AYAT, suras.sumOf { it.ayas })
        assertEquals(7, suras[0].ayas)
        assertEquals(286, suras[1].ayas)
        assertEquals(30, suras[66].ayas)
    }

    @Test
    fun textMatchesMetadataAyaForAya() {
        val quran = Assets.quran
        assertEquals(Tanzil.TOTAL_AYAT, quran.ayahCount)
        Assets.suras.forEach { assertEquals("āyāt in sūra ${it.index}", it.ayas, quran.suras[it.index - 1].size) }
        quran.suras.flatten().forEach { assertTrue(it.isNotBlank()) }
        assertTrue(quran.notice.contains("Tanzil Project"))
        assertTrue(quran.notice.contains("CHANGING IT IS NOT ALLOWED"))
    }

    @Test
    fun basmalaIsSplitOffEverySuraButFatihaAndTawba() {
        val quran = Assets.quran
        val fatiha1 = quran.text(AyahRef(1, 1))!!
        assertEquals(112, quran.basmala.size)
        assertNull(quran.basmalaOf(1))
        assertNull(quran.basmalaOf(9))
        assertEquals(fatiha1, quran.basmalaOf(2))
        // Al-Baqara 2:1 is just "Alif Lām Mīm" once the basmala is set apart.
        assertEquals("الٓمٓ", quran.text(AyahRef(2, 1)))
        // Only āya 1 is touched: an-Naml 27:30 keeps the basmala inside the āya.
        assertTrue(quran.text(AyahRef(27, 30))!!.endsWith(fatiha1))
    }

    @Test
    fun splitKeepsEveryCharacter() {
        val raw = Assets.file("quran/quran-uthmani.txt").readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        val quran = Assets.quran
        for (line in raw) {
            val (s, a, text) = line.split('|', limit = 3)
            val ref = AyahRef(s.toInt(), a.toInt())
            val rebuilt = listOfNotNull(if (ref.aya == 1) quran.basmalaOf(ref.sura) else null, quran.text(ref)).joinToString(" ")
            assertEquals("$ref", text, rebuilt)
        }
    }

    @Test
    fun translationsHaveEveryAya() {
        for (name in listOf("fr.hamidullah.txt", "en.sahih.txt")) {
            val parsed = Tanzil.parse(Assets.file("quran/$name").readLines().asSequence())
            assertEquals(name, 114, parsed.size)
            Assets.suras.forEach { assertEquals("$name sūra ${it.index}", it.ayas, parsed[it.index]!!.size) }
        }
    }
}
