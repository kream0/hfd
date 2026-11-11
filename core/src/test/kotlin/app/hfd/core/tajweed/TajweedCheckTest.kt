package app.hfd.core.tajweed

import app.hfd.core.Assets
import app.hfd.core.quran.AyahRef
import app.hfd.core.recite.Arabic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The timing of the long vowels ([TajweedCheck]): what it measures on, and what it flags. */
class TajweedCheckTest {
    private fun words(ref: String) = Arabic.words(Assets.quran.text(AyahRef.parse(ref)!!)!!)

    @Test
    fun aStretchOfTwoAyatIsPlacedInTheTextSaid() {
        val ayat = listOf(words("1:1"), words("1:2"))
        val opening = listOf("x", "yy")
        // From the basmala's third word to al-ḥamdu li-llāhi rabbi.
        val from = 2
        val to = 7
        val said = (opening + ayat.flatten().subList(from, to)).joinToString(" ")
        val beats = TajweedCheck.stretch(ayat, from, to, opening)
        assertTrue(beats.isNotEmpty())
        // Each beat points at its own consonant in the text said, and names its word among all the words.
        var base = 0
        for (aya in ayat) {
            val text = aya.joinToString(" ")
            for (b in Tajweed.beats(text)) {
                val flat = base + b.word
                if (flat !in from until to) continue
                val placed = beats.single { it.word == flat && said[it.at] == text[b.at] && it.vowel == b.vowel && it.madd == b.madd && said.substring(it.at).startsWith(text.substring(b.at).substringBefore(' ')) }
                assertEquals(b.copy(at = placed.at, word = flat), placed)
            }
            base += aya.size
        }
        assertEquals(beats.map { it.at }, beats.map { it.at }.sorted())
    }

    private fun timed(madd: Rule?, seconds: Float, paused: Boolean = false, last: Boolean = false) =
        TajweedCheck.Timed(Beat(at = 0, word = 0, vowel = if (madd != null) Vowel.LONG else Vowel.SHORT, madd = madd), seconds, paused, last)

    @Test
    fun aMaddHeldLikeANaturalOneIsFlagged() {
        val natural = List(5) { timed(Rule.MADD_NATURAL, 0.28f + 0.04f * (it % 3)) } // usual: 0.32 s
        fun found(vararg more: TajweedCheck.Timed) = TajweedCheck.check(natural + more).map { it.rule to it.fault }
        // Held as long as a plain long vowel: not held.
        assertEquals(listOf(Rule.MADD_MUTTASIL to TajweedCheck.Fault.TOO_SHORT), found(timed(Rule.MADD_MUTTASIL, 0.36f)))
        assertEquals(listOf(Rule.MADD_MUNFASIL to TajweedCheck.Fault.TOO_SHORT), found(timed(Rule.MADD_MUNFASIL, 0.40f)))
        // Four ḥarakāt exactly (about 1.7 natural beats), and more: right.
        assertEquals(emptyList<Any>(), found(timed(Rule.MADD_MUTTASIL, 0.56f), timed(Rule.MADD_MUNFASIL, 1.2f)))
        // Stopping on the word, the vowel before the next word's hamza is a plain one.
        assertEquals(emptyList<Any>(), found(timed(Rule.MADD_MUNFASIL, 0.32f, paused = true), timed(Rule.MADD_MUNFASIL, 0.32f, last = true)))
        // Six ḥarakāt asks for more.
        assertEquals(listOf(Rule.MADD_LAZIM to TajweedCheck.Fault.TOO_SHORT), found(timed(Rule.MADD_LAZIM, 0.56f)))
        assertEquals(emptyList<Any>(), found(timed(Rule.MADD_LAZIM, 0.8f)))
        // What may be two, four or six, and a plain long vowel, are never flagged.
        assertEquals(emptyList<Any>(), found(timed(Rule.MADD_ARID, 0.2f), timed(Rule.MADD_NATURAL, 1.5f)))
    }

    @Test
    fun withoutEnoughNaturalMaddsNothingIsSaid() {
        val few = listOf(timed(Rule.MADD_NATURAL, 0.3f), timed(Rule.MADD_NATURAL, 0.3f), timed(Rule.MADD_MUTTASIL, 0.3f))
        assertEquals(null, TajweedCheck.natural(few))
        assertEquals(emptyList<Any>(), TajweedCheck.check(few))
    }

    @Test
    fun aBreathIsNotCounted() {
        // 1 s of voice, 0.4 s of silence, 0.6 s of voice.
        val pcm = FloatArray(32000) { i -> if (i in 16000 until 22400) 0f else 0.3f * kotlin.math.sin(i * 0.2).toFloat() }
        val pause = TajweedCheck.pauses(pcm, 50)
        assertEquals((25 until 35).toList(), pause.indices.filter { pause[it] })
        val beats = listOf(Beat(0, 0, Vowel.SHORT), Beat(1, 0, Vowel.LONG, Rule.MADD_MUNFASIL), Beat(2, 1, Vowel.SHORT, last = true))
        val timed = TajweedCheck.time(beats, intArrayOf(5, 20, 40), pause)
        // (15, 10 and 10 frames of voice: the second beat's ten frames of pause are not counted.)
        assertEquals(listOf(15, 10, 10), timed.map { Math.round(it.seconds / TajweedCheck.FRAME) })
        assertEquals(listOf(false, true, false), timed.map { it.paused })
    }
}
