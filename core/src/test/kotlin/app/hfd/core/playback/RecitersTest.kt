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
