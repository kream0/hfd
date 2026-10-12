package app.hfd.core.recite

import app.hfd.core.Assets
import app.hfd.core.quran.AyahRef
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The marker while an āya's last word is held (the owner, 12 Oct, 1.15.4): readings come every
 * 0.3 s, and steered ones complete ٱلْعَٰلَمِينَ from its first letters, so the marker went on to the
 * next āya's first word for the 2.5 s the madd still lasted. It stays on the word being said
 * while the voice goes on, and moves to the word to say once the voice pauses.
 */
class FollowerMarkerTest {
    private val fatiha = (1..7).map { ReciteTarget(AyahRef(1, it), Arabic.words(Assets.quran.text(AyahRef(1, it)).orEmpty())) }

    /** As ReciteSession shows it (all words shown). */
    private val Follower.marker: Int get() = voice ?: tracker.position

    /** The owner's readings up to the [n]th line of [kind] [id] ("partial 2"), replayed. */
    private fun replay(kind: String, id: Int, n: Int = 1): Follower {
        val f = Follower(fatiha)
        var seen = 0
        for (line in javaClass.getResourceAsStream("/recite/owner-1.15.4-fatiha-marker.log")!!.bufferedReader().readLines()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val p = line.split(" ", limit = 4)
            if (p[1] == "session") continue
            f.heard(p[2].toInt(), p.getOrElse(3) { "" }, p[1] == "final")
            if (p[1] == kind && p[2].toInt() == id && ++seen == n) break
        }
        return f
    }

    @Test
    fun theMarkerStaysOnAnAyasLastWordWhileItIsHeld() {
        // 1:1's ٱلرَّحِيمِ heard (the 11th reading of utterance 1), then held for three readings more.
        for (n in 11..14) assertEquals(3, replay("partial", 1, n).marker)
        // The voice paused: the marker is on 1:2's first word, the word to say.
        assertEquals(4, replay("final", 1).marker)
        // 1:2's ٱلْعَٰلَمِينَ heard (the 23rd reading of utterance 2), then held 2.5 s: on it, not on 1:3's ٱلرَّحْمَٰنِ.
        for (n in 23..30) assertEquals(7, replay("partial", 2, n).marker)
        // Said on into 1:3 without a pause: the marker goes with the voice.
        assertEquals(9, replay("partial", 2, 31).marker)
    }

    @Test
    fun aWordHeldBackIsTheWordBeingSaid() {
        // A partial reading's last word not right yet waits (Follower.holdEdge): the marker is on it.
        val f = Follower(fatiha)
        f.heard(0, "بِسْمِ اللَّهِ الرَّحْمَنِ الرَّحِيمِ", final = true)
        f.heard(1, "الْحَمْدُ لِلَّهِ رَبِّ الْعَالَ", final = false)
        assertEquals(7, f.tracker.position)
        assertEquals(7, f.marker)
        f.heard(1, "الْحَمْدُ لِلَّهِ رَبِّ", final = false)
        assertEquals(6, f.marker)
    }
}
