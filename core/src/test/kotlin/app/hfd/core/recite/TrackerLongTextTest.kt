package app.hfd.core.recite

import app.hfd.core.Assets
import app.hfd.core.quran.AyahRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A whole sūra recited (the Qur'an section): the text is thousands of words, and each reading
 * looks for what was heard among the words around the recitation and at the text's opening, not
 * among all of it (Tracker.LOOK_BACK).
 */
class TrackerLongTextTest {
    /** Al-Baqara from its second āya (286 āyāt, some 6,100 words). */
    private val baqara = (2..286).map { ReciteTarget(AyahRef(2, it), Arabic.words(Assets.quran.text(AyahRef(2, it)).orEmpty())) }

    private fun Tracker.said(from: Int, to: Int) = feed(text(from, to))

    /** A tracker that has recited the first [words] words, an utterance of a dozen words at a time. */
    private fun recitedTo(words: Int): Tracker {
        val t = Tracker(baqara)
        while (t.position < words) {
            val before = t.position
            t.said(before, minOf(words, before + 12))
            assertTrue("stuck at $before", t.position > before)
        }
        return t
    }

    @Test
    fun aLongTextIsFollowedToItsEnd() {
        val t = Tracker(baqara)
        val started = System.nanoTime()
        while (!t.done) {
            val before = t.position
            t.said(before, minOf(t.size, before + 15))
            assertTrue("stuck at $before", t.position > before)
        }
        assertTrue(t.status.all { it == WordStatus.OK })
        // Some four hundred utterances over six thousand words: a reading costs milliseconds, not
        // more as the recitation goes on (it took the whole text so far at each one).
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $ms ms", ms < 20_000)
    }

    @Test
    fun anAyaSaidAgainIsFoundAmongTheWordsAround() {
        val t = recitedTo(1000)
        val (aya, _) = t.locate(990)
        // The āya before the one being recited, said again: the recitation goes back to it.
        val start = t.startOf(aya - 1)
        val moved = t.said(start, start + 8)
        assertTrue("moved $moved", moved < 0)
        assertEquals(start + 8, t.position)
    }

    @Test
    fun theTextStartedOverIsFoundAtItsOpening() {
        val t = recitedTo(1000)
        // From the top again (2:2's first words), far beyond the words around.
        val moved = t.said(0, 9)
        assertTrue("moved $moved", moved < 0)
        assertEquals(9, t.position)
    }
}
