package app.hfd.core.recite

import app.hfd.core.Assets
import app.hfd.core.quran.AyahRef
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Al-Baqara's opening as the CTC recogniser read it on the bench (10 Nov, ad-Dossary): the
 * basmala, the disconnected letters (ten seconds of alif lām mīm, written as two long words of
 * noise), then the text.
 */
class FollowerLettersTest {
    private val baqara = (1..5).map { ReciteTarget(AyahRef(2, it), Arabic.words(Assets.quran.text(AyahRef(2, it)).orEmpty())) }

    /** The follower after the last reading of utterance [through]. */
    private fun replay(through: Int, f: Follower = Follower(baqara)): Follower {
        for (line in javaClass.getResourceAsStream("/recite/bench-ctc-baqara-opening.log")!!.bufferedReader().readLines()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val p = line.split(" ", limit = 4)
            if (p[1] == "session") continue
            if (p[2].toInt() > through) break
            f.heard(p[2].toInt(), p.getOrElse(3) { "" }, p[1] == "final")
        }
        return f
    }

    private fun Follower.words(n: Int) = tracker.status.take(n).joinToString("") { it.name.take(1) }

    @Test
    fun theBasmalaIsNotTheLetters() {
        // Its readings as it grew (بسْ, بِسْمِلم, بِسْمِ ٱللَّهِ …) said nothing of the text.
        val f = replay(0)
        assertEquals(0, f.tracker.position)
        assertEquals("PPP", f.words(3))
    }

    @Test
    fun theLettersSoundTakesNoWordAfterThem() {
        // «بِسٱللَّ ٱلررَّحَحِمٓ» for alif lām mīm: the letters, and not ذلك الكتاب لا with them.
        val f = replay(1)
        assertEquals(1, f.tracker.position)
        assertEquals("OPPP", f.words(4))
        assertEquals(null, f.toCheck(1))
    }

    @Test
    fun theTextFollowsThem() {
        val f = replay(2)
        assertEquals(8, f.tracker.position)
        assertEquals("OOOOOOOO", f.words(8))
    }

    @Test
    fun wordsTakenForNoiseAreRightOnceSaid() {
        // As before the letters' rule: the noise left ذلك missed and الكتاب wrong, and the
        // recitation after لا. Said then, in a phrase that goes on past them, they are right.
        val f = Follower(baqara)
        val t = f.tracker
        t.reset(Tracker.Mark(4, Array(t.size) { listOf(WordStatus.OK, WordStatus.MISSED, WordStatus.WRONG, WordStatus.OK).getOrElse(it) { WordStatus.PENDING } }))
        f.heard(2, "ذَٰلِكَ ٱلْكِتَـٰبُ لَا رَيْبَ فِيهِ هُدًى لِّلْمُتَّقِينَ", final = true)
        assertEquals(8, t.position)
        assertEquals("OOOOOOOO", f.words(8))
    }
}
