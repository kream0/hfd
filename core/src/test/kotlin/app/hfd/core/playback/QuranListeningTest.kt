package app.hfd.core.playback

import app.hfd.core.Assets
import app.hfd.core.quran.AyahRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuranListeningTest {
    @Test
    fun aSurasSessionIdReadsBack() {
        for (s in 1..114) assertEquals(s, QuranListening.suraOf(QuranListening.id(s)))
        // A passage's id, or a sūra that doesn't exist, isn't one.
        for (id in listOf(null, "kursi", "all", "sura:", "sura:0", "sura:115", "sura:x", "sura:2:1")) assertNull(id, QuranListening.suraOf(id))
    }

    @Test
    fun eachSuraIsFollowedByTheNextUntilTheLast() {
        assertEquals(2, QuranListening.after(1))
        assertEquals(114, QuranListening.after(113))
        assertNull(QuranListening.after(114))
        assertNull(QuranListening.before(1))
        assertEquals(113, QuranListening.before(114))
    }

    @Test
    fun theSurasTogetherAreTheWholeQuran() {
        val quran = Assets.quran
        var total = 0
        for (s in 1..QuranListening.SURAS) {
            val ayat = QuranListening.ayat(s, quran.suras[s - 1].size)
            assertEquals(AyahRef(s, 1), ayat.first())
            assertTrue(ayat.all { quran.text(it) != null })
            total += ayat.size
        }
        assertEquals(6236, total)
    }

    @Test
    fun onlyRecitationsWithEveryAyaAreOffered() {
        assertTrue(QuranListening.RECITERS.isNotEmpty())
        assertTrue(QuranListening.RECITERS.none { it.timings != null })
        // The passages' reciter is kept when it has every āya; a whole-sūra one falls back.
        assertEquals("husary", QuranListening.reciter(null, "husary").id)
        assertEquals("alafasy", QuranListening.reciter("alafasy", "husary").id)
        val wholeSura = Reciters.ALL.first { it.timings != null }
        assertNull(QuranListening.reciter(null, wholeSura.id).timings)
        assertNull(QuranListening.reciter(wholeSura.id, wholeSura.id).timings)
    }
}
