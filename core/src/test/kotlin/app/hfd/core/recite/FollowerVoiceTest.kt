package app.hfd.core.recite

import app.hfd.core.Assets
import app.hfd.core.quran.AyahRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FollowerVoiceTest {
    private val imran = (1..4).map { ReciteTarget(AyahRef(3, it), Arabic.words(Assets.quran.text(AyahRef(3, it)).orEmpty())) }

    private fun recited(): Follower = Follower(imran).also { f ->
        f.heard(0, "الم", final = true)
        f.heard(1, "اللَّهُ لَا إِلَهَ إِلَّا هُوَ الْحَيُّ الْقَيُّومُ", final = true)
        f.heard(2, "نَزَّلَ عَلَيْكَ الْكِتَابَ بِالْحَقِّ مُصَدِّقًا لِمَا بَيْنَ يَدَيْهِ وَأَنْزَلَ التَّوْرَاةَ وَالْإِنْجِيلَ", final = true)
    }

    @Test
    fun theMarkerFollowsAnAyaSaidAgainBeforeTheRecitationGoesBack() {
        // The owner (11 Oct): saying an āya again, the next-word marker stayed where the
        // recitation was furthest. Going back takes some five words; the voice is followed sooner.
        val f = recited()
        val furthest = f.tracker.position
        // 3:2's first word: an āya said again from its start shows from it (ٱللَّهُ is also لَآ إِلَٰهَ to
        // the letters without alifs; the āya's start wins the tie, 12 Oct).
        f.heard(3, "اللَّهُ", final = false)
        assertEquals(AyahRef(3, 2) to 0, f.voice?.let { f.tracker.locate(it).let { (a, w) -> imran[a].ref to w } })
        f.heard(3, "اللَّهُ لَا إِلَهَ", final = false)
        assertEquals(furthest, f.tracker.position)
        // On the word said last (إِلَٰهَ), while the voice goes on.
        assertEquals(AyahRef(3, 2) to 2, f.voice?.let { f.tracker.locate(it).let { (a, w) -> imran[a].ref to w } })
        // Clearly said again: the recitation goes back there, and the marker is at its position.
        f.heard(3, "اللَّهُ لَا إِلَهَ إِلَّا هُوَ الْحَيُّ الْقَيُّومُ", final = true)
        assertEquals(f.tracker.startOf(2), f.tracker.position)
        assertNull(f.voice)
        // Going on from there: the voice is on the word said last, at the position's.
        f.heard(4, "نَزَّلَ عَلَيْكَ الْكِتَابَ", final = false)
        assertEquals(f.tracker.position - 1, f.voice)
    }

    @Test
    fun theLastWordsSaidAgainLeaveTheMarkerWhereItIs() {
        val f = recited()
        f.heard(3, "التَّوْرَاةَ وَالْإِنْجِيلَ", final = true)
        assertNull(f.voice)
    }
}
