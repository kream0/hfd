package app.hfd.core.playback

import app.hfd.core.Assets
import app.hfd.core.HfdJson
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
    fun theSectionsReciterIsTheOneChosenThereElseThePassages() {
        assertEquals("husary", QuranListening.choice(null, "husary").id)
        assertEquals("alafasy", QuranListening.choice("alafasy", "husary").id)
        assertEquals("badr", QuranListening.choice("badr", "husary").id)
        assertEquals(Reciters.DEFAULT.id, QuranListening.choice("nobody", null).id)
    }

    @Test
    fun aWholeSuraRecitationRecitesTheSurasItIsCutFor() {
        val badr = Reciters.byId("badr")
        val timings = HfdJson.decodeFromString(AyahTimings.serializer(), Assets.file(badr.timings!!).readText())
        fun sura(n: Int) = QuranListening.ayat(n, Assets.quran.suras[n - 1].size)
        // The owner chose at-Turkī for al-Kahf (10 Nov): every āya of it is cut, so he recites it.
        for (n in listOf(1, 18, 36, 67, 112)) assertEquals("sūra $n", badr, QuranListening.reciterFor(badr, timings, sura(n)))
        // Al-Baqara is only cut for its passages: a recitation that has every āya takes over.
        val other = QuranListening.reciterFor(badr, timings, sura(2))
        assertEquals(QuranListening.FALLBACK, other)
        assertNull(other.timings)
        // Without its cuts (the asset unread), nothing is his.
        assertEquals(QuranListening.FALLBACK, QuranListening.reciterFor(badr, null, sura(18)))
        // One that has every āya recites any sūra.
        val husary = Reciters.byId("husary")
        for (n in listOf(2, 18, 114)) assertEquals(husary, QuranListening.reciterFor(husary, null, sura(n)))
    }
}
