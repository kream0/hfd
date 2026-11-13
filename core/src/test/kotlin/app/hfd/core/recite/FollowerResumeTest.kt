package app.hfd.core.recite

import app.hfd.core.Assets
import app.hfd.core.quran.AyahRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Going back, then taking it up again where one had got to (the owner, 13 Nov, on 2.9.1: "I
 * recite up to the middle of an āya, go back to its start, change my mind and want to pick up
 * from the middle: I'm stuck, I have to recite all the way to the middle again. Same if I recite
 * five āyāt and then one already recited: it goes back there and I can't get back to where I was").
 */
class FollowerResumeTest {
    private val kahf = (1..10).map { ReciteTarget(AyahRef(18, it), Arabic.words(Assets.quran.text(AyahRef(18, it)).orEmpty())) }
    private fun aya(n: Int) = kahf[n - 1].words
    private fun start(n: Int) = kahf.take(n - 1).sumOf { it.words.size }

    private fun follower(strict: Boolean) = Follower(kahf, skip = Tracker.WAIT_WORDS).also { it.waits = true; it.tracker.strict = strict }

    /** A whole utterance, as it grows word by word, then settled as the app does. */
    private fun Follower.say(id: Int, words: List<String>): Int? {
        for (n in 1..words.size) heard(id, words.take(n).joinToString(" "), final = n == words.size)
        toCheck(id)?.let { c -> checked(c, List(c.opening + c.to - c.from) { 30f }) }
        return settle(id)
    }

    private fun Follower.red() = tracker.status.count { it == WordStatus.WRONG || it == WordStatus.MISSED }

    @Test
    fun backToAnAyasStartThenOnFromItsMiddle() {
        for (strict in listOf(false, true)) {
            val f = follower(strict)
            // 18:1 to its seventh word.
            f.say(0, aya(1).take(7))
            assertEquals(7, f.tracker.position)
            // Its first words again: the recitation is back there.
            f.say(1, aya(1).take(3))
            assertEquals(3, f.tracker.position)
            // Then on from where it had got to, without saying the words between again.
            f.say(2, aya(1).drop(7).take(4))
            assertEquals(11, f.tracker.position)
            assertEquals(0, f.red())
            assertTrue((0 until 11).all { f.tracker.status[it] == WordStatus.OK })
            assertEquals(11, f.tracker.frontier.coerceAtLeast(f.tracker.position))
        }
    }

    @Test
    fun ontoAnAyaAlreadyRecitedThenBackToWhereOneWas() {
        for (strict in listOf(false, true)) {
            val f = follower(strict)
            for (n in 1..5) f.say(n, aya(n))
            assertEquals(start(6), f.tracker.position)
            // 18:2 again, by mistake: the recitation goes back to it.
            f.say(6, aya(2).take(6))
            assertEquals(start(2) + 6, f.tracker.position)
            // Then 18:6, where the recitation had got to: it is followed at once (as it grows).
            val sixth = aya(6)
            f.heard(7, sixth.take(1).joinToString(" "), final = false)
            f.heard(7, sixth.take(3).joinToString(" "), final = false)
            assertEquals(start(6) + 3, f.tracker.position)
            f.heard(7, sixth.joinToString(" "), final = true)
            assertNull(f.settle(7))
            assertEquals(start(7), f.tracker.position)
            assertEquals(0, f.red())
            assertTrue((0 until start(7)).all { f.tracker.status[it] == WordStatus.OK })
            // What that utterance said is 18:6 alone (for the check, the timing): not the āyāt between.
            assertEquals(sixth.joinToString(" "), f.read(7)!!.text)
        }
    }

    @Test
    fun takenUpAgainAnywhereAmongTheWordsRecited() {
        val f = follower(strict = true)
        for (n in 1..5) f.say(n, aya(n))
        f.say(6, aya(1).take(4))
        assertEquals(4, f.tracker.position)
        // 18:4 from its second word (a phrase: more than a word or two): followed from there.
        f.say(7, aya(4).drop(1))
        assertEquals(start(5), f.tracker.position)
        assertEquals(0, f.red())
        // And again further on, where it had got to.
        f.say(8, aya(6).take(4))
        assertEquals(start(6) + 4, f.tracker.position)
        assertTrue((0 until start(6) + 4).all { f.tracker.status[it] == WordStatus.OK })
    }

    @Test
    fun neverPastWhatWasNotSaid() {
        val f = follower(strict = true)
        for (n in 1..3) f.say(n, aya(n))
        f.say(4, aya(1).take(4))
        assertEquals(4, f.tracker.position)
        // 18:5, which was never reached (18:4 was not said): nothing moves.
        f.say(5, aya(5))
        assertEquals(4, f.tracker.position)
        // Nor 18:4 from its fifth word on: its first words were not said.
        f.say(6, aya(4).drop(4))
        assertEquals(4, f.tracker.position)
        assertEquals(0, f.red())
        // 18:4 from its first word: on from where it had got to.
        f.say(7, aya(4))
        assertEquals(start(5), f.tracker.position)
        assertEquals(0, f.red())
    }

    @Test
    fun sayingItAllAgainStillFollowsWordByWord() {
        // Gone back, the reciter says it all again: followed from there as before, not jumped on.
        val f = follower(strict = true)
        for (n in 1..3) f.say(n, aya(n))
        f.say(4, aya(1).take(4))
        f.say(5, aya(1).drop(4))
        assertEquals(start(2), f.tracker.position)
        f.say(6, aya(2))
        assertEquals(start(3), f.tracker.position)
        assertEquals(0, f.red())
    }
}
