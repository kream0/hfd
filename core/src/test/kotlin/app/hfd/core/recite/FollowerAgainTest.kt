package app.hfd.core.recite

import app.hfd.core.Assets
import app.hfd.core.quran.AyahRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An āya said again (the owner, 11 Oct, 1.15.3): the recitation went back to it only from some
 * eight letters on, onto the word after the one being said, which in al-Fātiḥa's 1:1 is its last
 * word; and before that a few letters of it (بسم الل) were taken for 1:4's مالك, the marker
 * jumping on. The marker now follows from the āya's first word, and is on the word being said.
 */
class FollowerAgainTest {
    private val fatiha = (1..7).map { ReciteTarget(AyahRef(1, it), Arabic.words(Assets.quran.text(AyahRef(1, it)).orEmpty())) }

    private val Follower.marker: Int get() = voice ?: tracker.position

    private fun skeletons(text: String) = text.split(" ").joinToString(" ") { Arabic.skeleton(it) }

    /** The owner's readings up to [until] ("partial 6 <text>"), replayed. */
    private fun replay(until: String): Follower {
        val f = Follower(fatiha)
        for (line in javaClass.getResourceAsStream("/recite/owner-1.15.3-fatiha-again.log")!!.bufferedReader().readLines()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val p = line.split(" ", limit = 4)
            if (p[1] == "session") continue
            f.heard(p[2].toInt(), p.getOrElse(3) { "" }, p[1] == "final")
            if ("${p[1]} ${p[2]} ${skeletons(p.getOrElse(3) { "" })}" == until.split(" ", limit = 3).let { "${it[0]} ${it[1]} ${skeletons(it[2])}" }) break
        }
        return f
    }

    @Test
    fun anAyaSaidAgainIsFollowedFromItsFirstWord() {
        // 1:1 said again after 1:3: from بسم on, the marker is on الله, not where the recitation stood.
        assertEquals(1, replay("partial 6 بِسْمِ").marker)
        // بسم الل isn't 1:4's مالك: the recitation stays, the marker on الله.
        val f = replay("partial 6 مِسْمِ اللِّ")
        assertEquals(10, f.tracker.position)
        assertEquals(1, f.marker)
        // Going back while ٱلرَّحْمَٰنِ is being said: the marker is on it, not on 1:1's last word.
        assertEquals(2, replay("partial 6 مِسْمِ اللَّهِ الرَّحْمِ").marker)
        assertEquals(3, replay("partial 6 مِسْمِ اللَّهِ الرَّحْمَنِ").marker)
    }

    @Test
    fun goingOnIsntTakenForSayingAgain() {
        // 1:3 (ٱلرَّحْمَٰنِ ٱلرَّحِيمِ, also 1:1's end) after 1:2 goes on.
        val f = Follower(fatiha)
        f.heard(0, "بِسْمِ اللَّهِ الرَّحْمَنِ الرَّحِيمِ", final = true)
        f.heard(1, "الْحَمْدُ لِلَّهِ رَبِّ الْعَالَمِينَ", final = true)
        f.heard(2, "الرَّحْمَنِ الرَّحِيمِ", final = true)
        assertEquals(10, f.tracker.position)
        assertTrue((0 until 10).all { f.tracker.status[it] == WordStatus.OK })
    }
}
