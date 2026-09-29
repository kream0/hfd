package app.hfd.core.recite

import app.hfd.core.quran.AyahRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class SegmenterTest {
    private val rnd = Random(3)

    /**
     * [seconds] of 20 ms frames: a 200 Hz voice at [amp] in syllables (a 40 ms near-silence after
     * each 200 ms, as between the sounds of speech), or room noise when [amp] is 0.
     */
    private fun frames(seconds: Double, amp: Float): List<FloatArray> = (0 until (seconds * 50).toInt()).map { f ->
        val a = if (f % 12 >= 10) amp * 0.02f else amp
        FloatArray(Segmenter.FRAME) { i ->
            val t = (f * Segmenter.FRAME + i) / Segmenter.RATE.toDouble()
            (a * sin(2 * PI * 200 * t)).toFloat() + (rnd.nextFloat() - 0.5f) * 0.0004f
        }
    }

    private fun Segmenter.feedAll(frames: List<FloatArray>) = frames.flatMap { feed(it) }

    @Test
    fun anUtteranceIsHandedOverWhileItGoesOnThenWholeAtThePause() {
        val s = Segmenter()
        s.feedAll(frames(1.0, 0f))
        val got = s.feedAll(frames(4.0, 0.01f)) + s.feedAll(frames(1.0, 0f))
        val partials = got.filter { !it.final }
        val finals = got.filter { it.final }
        assertTrue("partials: ${partials.size}", partials.size in 3..4)
        assertTrue(partials.zipWithNext().all { (a, b) -> b.pcm.size > a.pcm.size })
        assertEquals(1, finals.size)
        assertTrue(got.all { it.id == 0 })
        // The whole utterance: the voice, the pre-roll before it and the pause after.
        assertTrue(finals[0].seconds in 4.5f..5.1f)
    }

    @Test
    fun theNextUtteranceHasTheNextId() {
        val s = Segmenter()
        s.feedAll(frames(1.0, 0f))
        val first = s.feedAll(frames(2.0, 0.01f) + frames(1.0, 0f))
        val second = s.feedAll(frames(2.0, 0.01f)) + listOfNotNull(s.end())
        assertEquals(setOf(0), first.map { it.id }.toSet())
        assertEquals(setOf(1), second.map { it.id }.toSet())
        assertTrue(second.last().final)
    }

    @Test
    fun aLongRecitationWithoutPausesIsCutBeforeTheModelsLimit() {
        val s = Segmenter()
        s.feedAll(frames(1.0, 0f))
        val got = s.feedAll(frames(45.0, 0.01f))
        val finals = got.filter { it.final }
        assertEquals(2, finals.size)
        assertTrue(finals.all { it.seconds <= Segmenter.MAX_FRAMES * 0.02f + 0.01f })
        assertEquals(listOf(0, 1), finals.map { it.id })
    }

    @Test
    fun aRestartDropsWhatIsBeingHeard() {
        val s = Segmenter()
        s.feedAll(frames(1.0, 0f))
        val before = s.feedAll(frames(2.0, 0.01f))
        s.restart()
        val after = s.feedAll(frames(2.0, 0.01f) + frames(1.0, 0f))
        assertTrue(before.isNotEmpty() && before.all { it.id == 0 })
        assertTrue(after.all { it.id == 1 })
        assertTrue(after.last().final && after.last().seconds < 3.1f)
    }

    private val targets = listOf(
        ReciteTarget(AyahRef(112, 1), Arabic.words("قُلْ هُوَ ٱللَّهُ أَحَدٌ")),
        ReciteTarget(AyahRef(112, 2), Arabic.words("ٱللَّهُ ٱلصَّمَدُ")),
    )

    @Test
    fun aPartialReadingIsReplacedByTheNextOne() {
        val f = Follower(targets)
        assertEquals(3, f.heard(0, "قل هو الله", final = false))
        // The next reading of the same utterance heard it differently: it replaces the first.
        assertEquals(2, f.heard(0, "قل هو", final = false))
        assertEquals(WordStatus.PENDING, f.tracker.statusOf(0, 2))
        assertEquals(4, f.heard(0, "قل هو الله احد", final = true))
        // A later reading of a finished utterance changes nothing.
        assertEquals(0, f.heard(0, "قل", final = false))
        assertEquals(2, f.heard(1, "الله الصمد", final = true))
        assertTrue(f.tracker.done)
    }

    @Test
    fun theBasmalaBeforeASuraIsNotPartOfIt() {
        val f = Follower(targets)
        f.heard(0, "بسم الله الرحمن الرحيم قل هو الله احد", final = true)
        assertEquals(4, f.tracker.position)
        assertTrue((0..3).all { f.tracker.statusOf(0, it) == WordStatus.OK })
    }

    @Test
    fun aHintKeepsWhatWasFollowedAndIgnoresTheRestOfThatUtterance() {
        val f = Follower(targets)
        f.heard(0, "قل هو", final = false)
        assertEquals("ٱللَّهُ", f.hint(through = 0))
        f.heard(0, "قل هو الله احد", final = true)
        assertEquals(3, f.tracker.position)
        f.heard(1, "احد", final = true)
        assertEquals(4, f.tracker.position)
        assertEquals(WordStatus.HINTED, f.tracker.statusOf(0, 2))
    }

    @Test
    fun wordsHeardRightTwiceStayRightWhenALaterReadingDropsThem() {
        val f = Follower(targets)
        f.heard(0, "قل هو الله احد", final = false)
        f.heard(0, "قل هو الله احد الله", final = false)
        // The final reading of the longer stretch lost the start.
        f.heard(0, "الله الصمد", final = true)
        assertTrue((0..3).all { f.tracker.statusOf(0, it) == WordStatus.OK })
        assertTrue(f.tracker.done)
    }

    @Test
    fun theTrackerCatchesUpWhenWordsWerentHeard() {
        val ikhlas = listOf(
            ReciteTarget(AyahRef(112, 1), Arabic.words("قُلْ هُوَ ٱللَّهُ أَحَدٌ")),
            ReciteTarget(AyahRef(112, 2), Arabic.words("ٱللَّهُ ٱلصَّمَدُ")),
            ReciteTarget(AyahRef(112, 3), Arabic.words("لَمْ يَلِدْ وَلَمْ يُولَدْ")),
        )
        val t = Tracker(ikhlas)
        // A scrap further on doesn't move it…
        assertEquals(0, t.feed("لم"))
        // …a clear stretch does: the words passed over are missed.
        assertEquals(10, t.feed("الله الصمد لم يلد ولم يولد"))
        assertTrue((0..3).all { t.statusOf(0, it) == WordStatus.MISSED })
        assertTrue((0..1).all { t.statusOf(1, it) == WordStatus.OK })
        assertTrue(t.done)
    }
}
