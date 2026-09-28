package app.hfd.core.playback

import app.hfd.core.quran.AyahRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecitersTest {
    @Test
    fun urlsFollowEveryAyahLayout() {
        val r = Reciters.byId("husary-muallim")
        assertEquals("https://everyayah.com/data/Husary_Muallim_128kbps/002255.mp3", EveryAyah.url(r, AyahRef(2, 255)))
        assertEquals("114006.mp3", EveryAyah.fileName(AyahRef(114, 6)))
        assertEquals("001001.mp3", EveryAyah.fileName(EveryAyah.BASMALA))
        assertEquals(Reciters.DEFAULT, Reciters.byId("unknown"))
        assertEquals("maher", Reciters.DEFAULT.id)
    }

    @Test
    fun wholeSuraRecitationsGiveEachAyaAsARange() {
        assertEquals(AyahRef(2, 255), EveryAyah.refOf("002255.mp3"))
        assertEquals(null, EveryAyah.refOf("bismillah.mp3"))
        val t = AyahTimings(
            schema = 1, reciter = "x", base = "https://example.org/r/",
            suras = mapOf("112" to SuraAudio("112.mp3", 500_000)),
            ayat = mapOf("112:1" to listOf(1_000L, 21_000L)),
        )
        assertEquals("https://example.org/r/112.mp3", t.url(AyahRef(112, 1)))
        assertEquals(1_000L..20_999L, t.bytes(AyahRef(112, 1)))
        assertTrue(AyahRef(112, 2) !in t)
    }

    @Test
    fun idsAreUnique() {
        assertEquals(Reciters.ALL.size, Reciters.ALL.map { it.id }.toSet().size)
    }

    @Test
    fun gapsScaleWithTheRecitation() {
        assertEquals(5_000, Timing.gapMs(10_000, 0.5f))
        assertEquals(15_000, Timing.gapMs(10_000, 1.5f))
        assertEquals(0, Timing.gapMs(10_000, 0f))
        assertTrue(Timing.estimateMs("ٱللَّهُ لَآ إِلَٰهَ إِلَّا هُوَ", Reciters.DEFAULT) >= 1_500)
    }
}
