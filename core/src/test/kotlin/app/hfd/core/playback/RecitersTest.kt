package app.hfd.core.playback

import app.hfd.core.Assets
import app.hfd.core.HfdJson
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
    fun wholeSuraRecitersHaveEveryAyaOfThePassages() {
        val needed = Assets.fadail.fadail.flatMap { it.ayat }.toSet() + EveryAyah.BASMALA
        for (r in Reciters.ALL.filter { it.timings != null }) {
            val t = HfdJson.decodeFromString(AyahTimings.serializer(), Assets.file(r.timings!!).readText())
            assertEquals(r.folder, t.reciter)
            val missing = needed.filter { it !in t }
            assertTrue("${r.id} lacks $missing", missing.isEmpty())
            for (ref in needed) {
                val bytes = t.bytes(ref)!!
                val size = t.sura(ref)!!.size
                assertTrue("${r.id} $ref $bytes", bytes.first >= 0 && bytes.last < size && bytes.last - bytes.first > 2_000)
            }
        }
    }

    @Test
    fun theWordBeingRecitedIsTheLastOneStarted() {
        val t = WordTimings(1, "x", mapOf("112:1" to listOf(40, 95, 150, 230)))
        val ref = AyahRef(112, 1)
        assertEquals(null, t.wordAt(ref, 390))
        assertEquals(0, t.wordAt(ref, 400))
        assertEquals(1, t.wordAt(ref, 1_200))
        assertEquals(3, t.wordAt(ref, 9_000))
        assertEquals(null, t.wordAt(AyahRef(112, 2), 1_000))
    }

    @Test
    fun theColouredWordIsTheTimedOne() {
        val texts = Assets.fadail.fadail.flatMap { it.ayat }.map { Assets.quran.text(it).orEmpty() } +
            Assets.quran.basmalaOf(2).orEmpty()
        for (text in texts) {
            val ranges = app.hfd.core.recite.Arabic.wordRanges(text)
            assertEquals(text, app.hfd.core.recite.Arabic.words(text), ranges.map { (a, b) -> text.substring(a, b) })
        }
    }

    @Test
    fun wordTimingsHaveEveryWordOfThePassages() {
        val dir = java.io.File(Assets.dir, "audio/words")
        val needed = Assets.fadail.fadail.flatMap { it.ayat }.toSet() + EveryAyah.BASMALA
        for (r in Reciters.ALL) {
            val file = java.io.File(dir, "${r.id}.json")
            if (!file.exists()) continue // not made yet: the reading view lights the āya only
            val t = HfdJson.decodeFromString(WordTimings.serializer(), file.readText())
            assertEquals(r.id, t.reciter)
            for (ref in needed) {
                val starts = t.words[ref.key] ?: continue // an āya the tool couldn't fetch
                val n = app.hfd.core.recite.Arabic.words(Assets.quran.text(ref).orEmpty()).size
                assertEquals("${r.id} $ref", n, starts.size)
                assertTrue("${r.id} $ref $starts", starts.zipWithNext().all { (a, b) -> a <= b } && starts.first() >= 0)
            }
            val missing = needed.count { it.key !in t.words }
            assertTrue("${r.id} lacks $missing āyāt", missing <= needed.size / 50)
        }
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
