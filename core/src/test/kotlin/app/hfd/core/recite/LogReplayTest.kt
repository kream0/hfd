package app.hfd.core.recite

import app.hfd.core.Assets
import app.hfd.core.quran.AyahRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The owner's real attempts, as the phone's recogniser read them (the .log files in
 * resources/recite, from the diagnostics), replayed through [Follower] the way the app feeds it: utterance ids go on across
 * the microphone's restarts in a session (the recorder counts from 0 again each time).
 */
class LogReplayTest {
    private val imran = (1..9).map { ReciteTarget(AyahRef(3, it), Arabic.words(Assets.quran.text(AyahRef(3, it)).orEmpty())) }

    private val fatiha = (1..7).map { ReciteTarget(AyahRef(1, it), Arabic.words(Assets.quran.text(AyahRef(1, it)).orEmpty())) }

    /** Each session's follower after its readings (up to the first [until] reading, e.g. "final 6"). */
    private fun replay(name: String, targets: List<ReciteTarget> = imran, until: String? = null): List<Follower> {
        val sessions = ArrayList<Follower>()
        var base = 0
        var last = -1
        for (line in javaClass.getResourceAsStream("/recite/$name")!!.bufferedReader().readLines()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val parts = line.split(" ", limit = 4)
            if (until != null && sessions.isNotEmpty() && "${parts[1]} ${parts.getOrNull(2)}" == until) {
                sessions.last().heard(base + parts[2].toInt(), parts.getOrElse(3) { "" }, final = parts[1] == "final")
                break
            }
            when (parts[1]) {
                "session" -> {
                    sessions += Follower(targets).also { f ->
                        // "session 3:2": the app stood there (the āyāt before recited already).
                        parts.getOrNull(2)?.let { at ->
                            val (s, a) = at.split(':').map(String::toInt)
                            val position = f.tracker.startOf(targets.indexOfFirst { it.ref == AyahRef(s, a) })
                            f.tracker.reset(Tracker.Mark(position, Array(f.tracker.size) { if (it < position) WordStatus.OK else WordStatus.PENDING }))
                        }
                    }
                    base = 0; last = -1
                }
                "resume" -> base = last + 1
                else -> {
                    val id = base + parts[2].toInt()
                    last = maxOf(last, id)
                    sessions.last().heard(id, parts.getOrElse(3) { "" }, final = parts[1] == "final")
                }
            }
        }
        return sessions
    }

    private fun Follower.mistakes(aya: Int): List<Int> = tracker.finished().first { it.ref == AyahRef(3, aya) }.mistakes

    @Test
    fun theOwnersFirstAttemptEndsRight() {
        val f = replay("owner-1.8.2-imran.log")[0]
        // Heard: الم / الله لا اله الا هو الحي القيوم / نزل عليك الكتاب … وانزل التوراة والانجيل.
        assertEquals(emptyList<Int>(), f.mistakes(1))
        assertEquals(emptyList<Int>(), f.mistakes(2))
        // 3:3 was read right but for الكتاب (heard كتاب, without its article): at most that one.
        assertTrue("3:3 mistakes ${f.mistakes(3)}", f.mistakes(3).size <= 1)
        assertEquals(19, f.tracker.position)
    }

    @Test
    fun alFatihasRahmanRahimIsFollowedNotTakenForTheBasmalaSaidAgain() {
        // 1:3 (ٱلرَّحْمَٰنِ ٱلرَّحِيمِ) repeats the end of 1:1: heard after 1:2, it is 1:3.
        val f = replay("bench-fatiha-noisy.log", fatiha)[0]
        val t = f.tracker
        val start5 = t.startOf(4)
        assertTrue("position ${t.position}, want past 1:5 (${t.startOf(5)})", t.position >= t.startOf(5))
        for (aya in 2..4) {
            val r = t.resultOf(aya)
            assertTrue("1:${aya + 1} mistakes ${r.mistakes}", r.mistakes.size <= 1)
        }
        assertTrue(start5 > 0)
    }

    @Test
    fun aSkippedAyaFollowedByAPartialReadingStaysFollowed() {
        val t = replay("bench-imran-skip.log")[0].tracker
        val start3 = t.startOf(2)
        // 3:2 passed over, 3:3 followed (its first eight words were recited).
        assertTrue("position ${t.position}, 3:3 starts at $start3", t.position >= start3 + 6)
        val right = (start3 until start3 + 8).count { t.status[it] == WordStatus.OK }
        assertTrue("3:3: $right of 8 right", right >= 5)
    }

    @Test
    fun aBreathAtAnAyasStartDoesntLightItsWords() {
        // 1.14.1, 2 Oct: after 3:4, an utterance of 1.3 s read أَنْ then أَهْ lit 3:5's إِنَّ اللَّهَ,
        // said two seconds later. أَنْ may be إِنَّ begun; اللَّهَ wasn't heard.
        val t = replay("owner-1.14.1-imran.log", until = "final 6")[0].tracker
        val start5 = t.startOf(4)
        assertTrue("position ${t.position}, 3:5 starts at $start5", t.position <= start5 + 1)
        // Then 3:5 said whole is followed, right.
        val f = replay("owner-1.14.1-imran.log")[0]
        assertEquals(emptyList<Int>(), f.mistakes(5))
        assertTrue(f.tracker.position >= f.tracker.startOf(5))
    }

    @Test
    fun readingsAfterTheMicrophoneStartsAgainAreFollowed() {
        val f = replay("owner-1.8.2-imran.log")[1]
        // After the restart: الم, then والله لا اله الحي القيوم (3:2) and نزل عن … (3:3's start)
        // were heard; the text went on (the first try at 3:2 had been lost).
        assertTrue("position ${f.tracker.position}", f.tracker.position >= 8)
    }
}
