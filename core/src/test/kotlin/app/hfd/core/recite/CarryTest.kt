package app.hfd.core.recite

import app.hfd.core.Assets
import app.hfd.core.quran.AyahRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A pause inside a word (the bench, 13 Nov: Ḥuṣarī's slow recitation, held up seven times in
 * Āl ʿImrān's opening once words had to be said letter for letter): what an utterance ends on is
 * heard again with the next one.
 */
class CarryTest {
    private val imran = (1..4).map { ReciteTarget(AyahRef(3, it), Arabic.words(Assets.quran.text(AyahRef(3, it)).orEmpty())) }
    private val third = imran[2].words
    private val start = imran.take(2).sumOf { it.words.size }

    private fun follower() = Follower(imran, skip = Tracker.WAIT_WORDS).also { f ->
        f.waits = true
        f.tracker.strict = true
        f.tracker.startAt(start)
    }

    @Test
    fun aLetterTwoWordsCouldOwnIsTheEarlierWords() {
        // «وَأَنزَلَ ٱلتَّوْرَىٰةَ» with the breath taken inside the second word, as the recogniser wrote it.
        val f = follower()
        f.heard(0, (third.take(8) + "وَأَنزَلَت").joinToString(" "), final = true)
        val check = f.toCheck(0)!!
        // «وَأَنزَلَ» is whole; only the word begun is left for the check, and it wasn't said.
        assertEquals(listOf(start + 9), check.words)
        f.checked(check, List(check.to - check.from) { if (it == 9) 6.5f else 0.1f })
        assertEquals(start + 9, f.settle(0))
        assertTrue(f.atEdge)
        assertTrue((start until start + 9).all { f.tracker.status[it] == WordStatus.OK })
        // What it said of the text: up to «وَأَنزَلَ»; the «ت» after it is the next word's beginning.
        val said = f.edge(0)!!
        assertEquals(third.take(9).joinToString(" "), said)
        val reading = (third.take(8) + "وَأَنزَلَت").joinToString(" ")
        assertEquals(reading.length - 1, Carry.after(reading, said))
    }

    @Test
    fun whatFollowsTheWordsSaidIsKept() {
        // Nothing after them: nothing kept.
        assertEquals("بِسْمِ ٱللَّهِ".length, Carry.after("بِسْمِ ٱللَّهِ", "بِسْمِ ٱللَّهِ"))
        // A word begun after them: from its first character (the alif before its first letter).
        val reading = "بِسْمِ ٱللَّهِ ٱلرَّحْمَة"
        assertEquals(reading.indexOf("ٱلرَّ"), Carry.after(reading, "بِسْمِ ٱللَّهِ"))
        // A letter of theirs misheard: still after them.
        assertEquals(reading.indexOf("ٱلرَّ"), Carry.after(reading, "بِسْمِ ٱللَّهُ"))
        assertEquals("بِسْنِ ٱللَّهِ ".length, Carry.after("بِسْنِ ٱللَّهِ ٱلرَّحْمَة", "بِسْمِ ٱللَّهِ"))
        // Nothing of the text said: all of it.
        assertEquals(0, Carry.after("مّ", ""))
    }

    @Test
    fun onlyAWordsWorthAndOnlyIntoTheNextUtterance() {
        val rate = Segmenter.RATE
        val pcm = FloatArray(5 * rate) { it.toFloat() }
        val next = FloatArray(rate) { -1f }
        val carry = Carry()
        // The last second of an utterance, the recitation left at word 7.
        carry.keep(pcm, 4 * rate, word = 7)
        assertEquals(1f, carry.seconds, 0.001f)
        val joined = carry.join(1, next, position = 7)
        assertEquals(2 * rate, joined.size)
        assertEquals(pcm[4 * rate], joined[0])
        assertEquals(-1f, joined[rate])
        // The same utterance, read again as it grows: still with it. The one after: not.
        assertEquals(2 * rate, carry.join(1, next, position = 9).size)
        assertSame(next, carry.join(2, next, position = 9))
        assertEquals(0f, carry.seconds, 0f)
        // More than a word's worth, or next to nothing: not kept.
        carry.keep(pcm, rate, word = 7)
        assertEquals(0f, carry.seconds, 0f)
        carry.keep(pcm, pcm.size - 100, word = 7)
        assertEquals(0f, carry.seconds, 0f)
        // The recitation has moved since (a hint, another place): not joined.
        carry.keep(pcm, 4 * rate, word = 7)
        assertSame(next, carry.join(3, next, position = 8))
        assertEquals(0f, carry.seconds, 0f)
    }

    @Test
    fun byTheFramesTheRecogniserWroteAt() {
        // Ten frames a character, 100 frames a second of sound here: kept from two frames before
        // the first character after the words said.
        val reading = "بِسْمِ ٱللَّهِ ٱلرَّحْمَة"
        val placed = IntArray(reading.length) { it * 10 }
        val frames = reading.length * 10
        val pcm = FloatArray(frames * 160)
        val carry = Carry()
        carry.keep(pcm, reading, placed, frames, "بِسْمِ ٱللَّهِ", word = 2)
        val at = reading.indexOf("ٱلرَّ")
        assertEquals((frames - (at * 10 - 2)) * 160 / Segmenter.RATE.toFloat(), carry.seconds, 0.001f)
        // It ended on the words said: nothing.
        carry.keep(pcm, reading, placed, frames, reading, word = 3)
        assertEquals(0f, carry.seconds, 0f)
    }

    @Test
    fun aStumbleKeepsNothing() {
        // A word left out with words right after it: what follows is to say again, nothing is kept.
        val f = follower()
        f.heard(0, (third.take(2) + third.drop(3).take(4)).joinToString(" "), final = true)
        f.toCheck(0)?.let { c -> f.checked(c, List(c.to - c.from) { if (it == 2) 30f else 0.1f }) }
        assertEquals(start + 2, f.settle(0))
        assertNull(f.edge(0))
    }
}
