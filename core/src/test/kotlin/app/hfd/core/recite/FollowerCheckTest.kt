package app.hfd.core.recite

import app.hfd.core.Assets
import app.hfd.core.quran.AyahRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowerCheckTest {
    private val imran = (1..9).map { ReciteTarget(AyahRef(3, it), Arabic.words(Assets.quran.text(AyahRef(3, it)).orEmpty())) }

    /** A follower that has heard 3:1, then 3:2 as the phone read it on 10 Oct (الحي القيوم misheard). */
    private fun misheard(): Follower = Follower(imran).also { f ->
        f.heard(0, "الم", final = true)
        f.heard(1, "اللَّهُ لَا إِلَهَ إِلَّا هُوَ الْحَيُّ حَيْظُهُمْ", final = true)
    }

    @Test
    fun aMistakeReadRightWhenHeldToTheTextIsRight() {
        val f = misheard()
        val c = f.toCheck(1)!!
        assertTrue("checked ${c.words}", 7 in c.words)
        assertTrue(c.expected.startsWith("?\n"))
        // Held to the text, the recogniser read الْقَيُّومُ at little cost (the words from 3:2's start).
        val costs = Follower.Check.costs("اللَّهُ لَا إِلَهَ إِلَّا هُوَ الْحَيُّ الْقَيُّومُ\t0.00,0.00,0.00,0.00,0.00,0.50,2.10")!!
        assertEquals(1, f.checked(c, costs))
        assertEquals(WordStatus.OK, f.tracker.status[7])
        assertEquals(8, f.tracker.position)
    }

    @Test
    fun aMistakeTheAudioHoldsStays() {
        val f = misheard()
        val c = f.toCheck(1)!!
        // Held to the text, it cost much (another word was said): the mistake stands.
        assertEquals(0, f.checked(c, listOf(0f, 0f, 0f, 0f, 0f, 0.5f, 11f)))
        // Costs that don't match the text change nothing.
        assertEquals(0, f.checked(c, listOf(0f, 0f)))
        assertTrue(f.tracker.status[7] != WordStatus.OK)
        assertEquals(8, f.tracker.position)
    }

    @Test
    fun aReadingThatCantBeHeldToTheTextRightsNothing() {
        // The owner skipped 3:2 and said 3:3 (10 Oct): held to 3:2 + 3:3, the short words of 3:2
        // cost little, then the reading fell off the text. The skipped words stay missed.
        val f = Follower(imran)
        f.heard(0, "الم", final = true)
        f.heard(1, "نَزَّلَ عَلَيْكَ الْكِتَابَ بِالْحَقِّ مُصَدِّقًا لِمَا بَيْنَ يَدَيْهِ", final = true)
        val c = f.toCheck(1)!!
        assertTrue("checked ${c.words}", c.words.isNotEmpty())
        val costs = listOf(4.96f, 2.61f, 3.15f, 1.45f, 5.36f) + List(c.to - c.from - 5) { Follower.CHECK_FAILED }
        val before = f.tracker.status.copyOf()
        assertEquals(0, f.checked(c, costs))
        assertTrue(f.tracker.status.contentEquals(before))
    }

    @Test
    fun nothingToCheckWithoutMistakes() {
        val f = Follower(imran)
        f.heard(0, "الم", final = true)
        f.heard(1, "اللَّهُ لَا إِلَهَ إِلَّا هُوَ الْحَيُّ الْقَيُّومُ", final = true)
        assertNull(f.toCheck(1))
        // Nor for an utterance other than the one just followed.
        assertNull(misheard().toCheck(0))
    }
}
