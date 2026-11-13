package app.hfd.core.recite

import app.hfd.core.Assets
import app.hfd.core.quran.AyahRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recitation waits for the reciter (the owner, 13 Nov, reciting in the Qur'an): a mistake,
 * then words that are also the text's some āyāt later, took the recitation there and turned
 * everything in between red; saying the right thing then took it back. It now stays at the word
 * the reciter left the text at until that word is said.
 */
class FollowerWaitTest {
    private val kahf = (1..10).map { ReciteTarget(AyahRef(18, it), Arabic.words(Assets.quran.text(AyahRef(18, it)).orEmpty())) }
    private fun aya(n: Int) = kahf[n - 1].words
    private fun start(n: Int) = kahf.take(n - 1).sumOf { it.words.size }

    private fun follower(free: Boolean) = Follower(kahf, skip = Tracker.WAIT_WORDS).also { it.waits = free }

    /** A whole utterance, read and settled as the app does (no mistake is righted by the check). */
    private fun Follower.say(id: Int, words: List<String>): Int? {
        heard(id, words.joinToString(" "), final = true)
        return settle(id)
    }

    private fun Follower.red() = tracker.status.count { it == WordStatus.WRONG || it == WordStatus.MISSED }

    @Test
    fun wordsOfALaterAyaMoveNothing() {
        for (free in listOf(true, false)) {
            val f = follower(free)
            f.say(0, aya(1))
            assertEquals(start(2), f.tracker.position)
            // 18:4 said after 18:1: neither 18:2 nor 18:3 is passed over.
            f.say(1, aya(4))
            assertEquals(start(2), f.tracker.position)
            assertEquals(0, f.red())
            // Nor the āya after next, nor one much further.
            f.say(2, aya(3))
            f.say(3, aya(9))
            assertEquals(start(2), f.tracker.position)
            assertEquals(0, f.red())
            // Taken up again where it was left: it goes on, with nothing to show for the detour.
            f.say(4, aya(2))
            assertEquals(start(3), f.tracker.position)
            assertEquals(0, f.red())
            assertTrue((0 until start(3)).all { f.tracker.status[it] == WordStatus.OK })
        }
    }

    @Test
    fun aVerseThatBeginsAsTheNextIsNotLeftForIt() {
        // al-Baqara 3 and 4 begin alike (ٱلَّذِينَ يُؤْمِنُونَ…): 2:4 said after 2:2, as its readings grow.
        val baqara = (2..5).map { ReciteTarget(AyahRef(2, it), Arabic.words(Assets.quran.text(AyahRef(2, it)).orEmpty())) }
        val second = baqara[0].words.size
        for (free in listOf(true, false)) {
            val f = Follower(baqara, skip = Tracker.WAIT_WORDS).also { it.waits = free }
            f.heard(0, baqara[0].words.joinToString(" "), final = true)
            f.settle(0)
            val said = baqara[2].words
            for (n in 1..said.size) f.heard(1, said.take(n).joinToString(" "), final = n == said.size)
            f.settle(1)
            // Its first two words are 2:3's; the recitation is no further than what follows them.
            assertTrue(f.tracker.position <= second + 2 + Tracker.WAIT_WORDS)
            assertTrue((second + 2 + Tracker.WAIT_WORDS until f.tracker.size).all { f.tracker.status[it] == WordStatus.PENDING })
            if (free) assertEquals(0, f.red())
        }
    }

    @Test
    fun theOldTrackerWentThere() {
        // (What 2.8.0 did, kept for the bench's comparison.)
        val f = Follower(kahf)
        f.heard(0, aya(1).joinToString(" "), true)
        f.heard(1, aya(4).joinToString(" "), true)
        assertEquals(start(5), f.tracker.position)
        assertTrue(f.red() >= aya(2).size + aya(3).size)
    }

    @Test
    fun freeRecitationWaitsAtAWordSaidWrong() {
        val f = follower(free = true)
        // 18:1 with its fifth word replaced by another.
        val said = aya(1).toMutableList().also { it[4] = aya(2)[0] }
        assertEquals(4, f.say(0, said))
        assertEquals(4, f.tracker.position)
        assertEquals(0, f.red())
        assertTrue(f.stumbled[4])
        assertTrue((4 until f.tracker.size).all { f.tracker.status[it] == WordStatus.PENDING })
        // Going on from after it isn't the word waited for.
        f.say(1, aya(2))
        assertEquals(4, f.tracker.position)
        // Said from that word: it goes on.
        assertNull(f.say(2, aya(1).drop(4)))
        assertEquals(start(2), f.tracker.position)
        assertEquals(0, f.red())
    }

    @Test
    fun whileSpeakingNothingShowsPastAWordNotRight() {
        val f = follower(free = true)
        val said = aya(1).toMutableList().also { it[4] = aya(2)[0] }
        f.heard(0, said.take(8).joinToString(" "), final = false)
        assertEquals(4, f.tracker.position)
        assertNull(f.voice)
        assertEquals(0, f.red())
    }

    @Test
    fun theWordsAnUtteranceEndsOnAreOnlyNotSaidYet() {
        val f = follower(free = true)
        // A pause inside 18:1, after a scrap of its fifth word.
        assertEquals(4, f.say(0, aya(1).take(4) + aya(1)[4].take(2)))
        assertTrue(f.atEdge)
        assertFalse(f.stumbled[4])
        assertEquals(4, f.tracker.position)
        // The word said after the pause: it goes on, no stumble.
        assertNull(f.say(1, aya(1).drop(4)))
        assertEquals(start(2), f.tracker.position)
        assertFalse(f.stumbled.any { it })
        // Left out by what is said next: a stumble.
        val g = follower(free = true)
        g.say(0, aya(1).take(4) + aya(1)[4].take(2))
        assertEquals(4, g.say(1, aya(1).drop(5)))
        assertFalse(g.atEdge)
        assertTrue(g.stumbled[4])
    }

    @Test
    fun nothingShowsPastItWhileTheCheckRuns() {
        val f = follower(free = true)
        val said = aya(1).toMutableList().also { it[4] = aya(2)[0] }
        // The whole utterance is read; its check hasn't ruled yet.
        f.heard(0, said.joinToString(" "), final = true)
        assertEquals(start(2), f.tracker.position)
        assertEquals(4, f.reach)
        f.settle(0)
        assertEquals(4, f.reach)
        // Marking mistakes, it shows as far as it went.
        val strict = follower(free = false)
        strict.heard(0, said.joinToString(" "), final = true)
        assertEquals(start(2), strict.reach)
    }

    @Test
    fun strictRecitationMarksItAndGoesOn() {
        val f = follower(free = false)
        val said = aya(1).toMutableList().also { it[4] = aya(2)[0] }
        assertNull(f.say(0, said))
        assertEquals(start(2), f.tracker.position)
        assertEquals(WordStatus.WRONG, f.tracker.status[4])
        assertEquals(1, f.red())
        assertFalse(f.stumbled[4])
    }

    @Test
    fun aWordTheCheckRightsIsNotWaitedAt() {
        val f = follower(free = true)
        // The recogniser dropped a word: the check finds it on the sound.
        val said = aya(1).filterIndexed { i, _ -> i != 4 }
        f.heard(0, said.joinToString(" "), final = true)
        val check = f.toCheck(0)!!
        assertEquals(listOf(4), check.words)
        assertEquals(1, f.checked(check, List(aya(1).size) { 0.5f }))
        assertNull(f.settle(0))
        assertEquals(start(2), f.tracker.position)
        assertFalse(f.stumbled[4])
    }

    @Test
    fun aWordRightBeforeStaysRightWhenSaidAgainBadly() {
        val f = follower(free = true)
        f.say(0, aya(1))
        f.say(1, aya(2))
        // 18:2 said again, a word of it read differently this time: no going back to wait there.
        val again = aya(2).toMutableList().also { it[2] = aya(1)[1] }
        assertNull(f.say(2, again))
        assertEquals(start(3), f.tracker.position)
        assertEquals(0, f.red())
    }

    @Test
    fun anAyaSaidAgainIsStillFollowed() {
        val f = follower(free = true)
        f.say(0, aya(1))
        f.say(1, aya(2))
        f.say(2, aya(1))
        assertEquals(start(2), f.tracker.position)
        f.say(3, aya(2))
        f.say(4, aya(3))
        assertEquals(start(4), f.tracker.position)
        assertEquals(0, f.red())
    }
}
