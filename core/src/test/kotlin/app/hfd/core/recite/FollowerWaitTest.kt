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

    private val enemy = listOf(ReciteTarget(AyahRef(2, 97), Arabic.words(Assets.quran.text(AyahRef(2, 97)).orEmpty())))

    /** As the app follows with the recogniser that writes what it hears: letter for letter, no word passed over. */
    private fun strict(targets: List<ReciteTarget>, free: Boolean) =
        Follower(targets, skip = Tracker.WAIT_WORDS).also { it.waits = free; it.tracker.strict = true }

    @Test
    fun aWordLeftOutIsNeverPassed() {
        // (The owner, 13 Nov: «قل كان» said for «قُلْ مَن كَانَ عَدُوًّا», and «كَانَ» lit after «قُلْ».)
        val words = enemy[0].words
        val f = strict(enemy, free = true)
        // As it grows: nothing shows past the word left out. Then whole, and checked.
        for (n in 1..4) {
            f.heard(0, (words.take(1) + words.drop(2).take(n)).joinToString(" "), final = n == 4)
            if (n < 4) assertEquals(1, f.tracker.position)
        }
        val check = f.toCheck(0)!!
        assertTrue(1 in check.unheard)
        // Held to the sound, a word that was not said can cost little (one time in ten under
        // 2.9.0's tolerance): not little enough for one the free reading did not hear.
        f.checked(check, List(check.to - check.from) { if (it == 1) 2.5f else 0.2f })
        assertEquals(1, f.settle(0))
        assertEquals(1, f.tracker.position)
        assertEquals(WordStatus.OK, f.tracker.status[0])
        assertTrue((1 until f.tracker.size).all { f.tracker.status[it] == WordStatus.PENDING })
        // Nor at an utterance's beginning: the word waited at left out again, what follows it said.
        f.heard(1, words.drop(2).take(4).joinToString(" "), final = true)
        f.toCheck(1)?.let { c -> f.checked(c, List(c.to - c.from) { if (it == 0) 30f else 0.2f }) }
        f.settle(1)
        assertEquals(1, f.tracker.position)
        // Said with the word: it goes on.
        f.heard(2, words.drop(1).take(4).joinToString(" "), final = true)
        assertNull(f.settle(2))
        assertEquals(5, f.tracker.position)
        // A word the free reading dropped and the sound clearly holds is still righted.
        val g = strict(enemy, free = true)
        g.heard(0, (words.take(1) + words.drop(2).take(4)).joinToString(" "), final = true)
        g.toCheck(0)!!.let { c -> g.checked(c, List(c.to - c.from) { 0.3f }) }
        assertNull(g.settle(0))
        assertEquals(6, g.tracker.position)
    }

    @Test
    fun aWordThatOnlyResemblesIsNotRight() {
        // 2:4's «يُوقِنُونَ» said «يُؤْمِنُونَ»: five letters of six. Not right until the check has listened.
        val baqara = listOf(ReciteTarget(AyahRef(2, 4), Arabic.words(Assets.quran.text(AyahRef(2, 4)).orEmpty())))
        val words = baqara[0].words
        val said = words.dropLast(1) + Arabic.words(Assets.quran.text(AyahRef(2, 3)).orEmpty())[1]
        val free = strict(baqara, free = true)
        assertEquals(words.lastIndex, free.say(0, said))
        assertEquals(words.lastIndex, free.tracker.position)
        assertEquals(0, free.red())
        val marking = strict(baqara, free = false)
        marking.say(0, said)
        assertEquals(WordStatus.WRONG, marking.tracker.status[words.lastIndex])
        // The check finds it said (the recogniser's own slip): right.
        val slip = strict(baqara, free = true)
        slip.heard(0, said.joinToString(" "), final = true)
        val check = slip.toCheck(0)!!
        assertEquals(listOf(words.lastIndex), check.words)
        slip.checked(check, List(words.size) { 0.3f })
        assertNull(slip.settle(0))
        assertEquals(words.size, slip.tracker.position)
        // (Without strictness, as with whisper.cpp's rougher letters, it is taken for the word.)
        val rough = follower(free = false).let { Follower(baqara, skip = Tracker.WAIT_WORDS) }
        rough.heard(0, said.joinToString(" "), final = true)
        assertEquals(WordStatus.OK, rough.tracker.status[words.lastIndex])
    }

    @Test
    fun aWordReadALetterOffDoesNotHoldBackTheRestOfThePhrase() {
        // (The owner on 2.10.0, 13 Nov: ten words said, shown three seconds later. One word read
        // a letter off early in a long phrase kept everything after it dark until the pause.)
        val words = aya(1)
        val off = words.toMutableList().also { it[1] = it[1].dropLast(2) + "م" }
        for (free in listOf(true, false)) {
            val f = strict(kahf, free).also { it.waits = true }
            // As the phrase grows, the second word a letter off in every reading.
            f.heard(0, off.take(3).joinToString(" "), final = false, hold = false)
            // Not at the phrase's edge any more: asked about at once, and the sound holds it.
            val check = f.toCheck(0, partial = true)!!
            assertEquals(listOf(1), check.words)
            assertEquals(1, f.checked(check, List(check.to - check.from) { if (it == 1) 1.5f else 0f }))
            f.hold(0)
            assertEquals(3, f.tracker.position)
            // The next readings go on past it, without asking again.
            for (n in 4..7) {
                f.heard(0, off.take(n).joinToString(" "), final = false, hold = false)
                assertNull(f.toCheck(0, partial = true))
                f.hold(0)
                assertEquals(n, f.tracker.position)
            }
            f.heard(0, off.take(8).joinToString(" "), final = true)
            assertNull(f.toCheck(0))
            assertNull(f.settle(0))
            assertEquals(8, f.tracker.position)
            assertEquals(0, f.red())
        }
        // A word the sound neither bears out nor rules out (the owner's 2.10.1: eleven stops in four
        // minutes of recitation, to each phrase's end) is only doubted: the text goes on past it,
        // that word not lit, and the whole phrase's check decides.
        val d = strict(kahf, free = true)
        d.heard(0, off.take(3).joinToString(" "), final = false, hold = false)
        d.toCheck(0, partial = true)!!.let { c -> assertEquals(0, d.checked(c, List(c.to - c.from) { if (it == 1) 3.2f else 0f })) }
        d.hold(0)
        assertEquals(3, d.tracker.position)
        assertEquals(3, d.reach)
        assertTrue(d.tracker.status[1] != WordStatus.OK)
        d.heard(0, off.take(6).joinToString(" "), final = false, hold = false)
        assertNull(d.toCheck(0, partial = true))
        d.hold(0)
        assertEquals(6, d.reach)
        // Whole, and refused: back to it, the words after it to say again.
        d.heard(0, off.take(6).joinToString(" "), final = true)
        d.toCheck(0)!!.let { c -> assertEquals(listOf(1), c.words); d.checked(c, List(c.to - c.from) { if (it == 1) 3.2f else 0f }) }
        assertEquals(1, d.settle(0))
        assertEquals(1, d.reach)
        // (Borne out once whole: it goes on.)
        val e = strict(kahf, free = true)
        e.heard(0, off.take(3).joinToString(" "), final = false, hold = false)
        e.toCheck(0, partial = true)!!.let { c -> e.checked(c, List(c.to - c.from) { if (it == 1) 2.8f else 0f }) }
        e.hold(0)
        e.heard(0, off.take(6).joinToString(" "), final = true)
        e.toCheck(0)!!.let { c -> assertEquals(1, e.checked(c, List(c.to - c.from) { if (it == 1) 2.8f else 0f })) }
        assertNull(e.settle(0))
        assertEquals(6, e.tracker.position)
        // A word the sound rules out stops the phrase there, asked about once while it grows,
        // and again once it is whole.
        val g = strict(kahf, free = true)
        g.heard(0, off.take(3).joinToString(" "), final = false, hold = false)
        g.toCheck(0, partial = true)!!.let { c -> assertEquals(0, g.checked(c, List(c.to - c.from) { if (it == 1) 6f else 0f })) }
        g.hold(0)
        assertEquals(1, g.tracker.position)
        g.heard(0, off.take(5).joinToString(" "), final = false, hold = false)
        assertNull(g.toCheck(0, partial = true))
        g.hold(0)
        assertEquals(1, g.tracker.position)
        g.heard(0, off.take(6).joinToString(" "), final = true)
        val whole = g.toCheck(0)!!
        assertEquals(listOf(1), whole.words)
        g.checked(whole, List(whole.to - whole.from) { if (it == 1) 6f else 0f })
        assertEquals(1, g.settle(0))
        assertEquals(1, g.tracker.position)
    }
}
