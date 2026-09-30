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

    /** Each session's follower after its readings, and the log lines of its readings. */
    private fun replay(name: String): List<Follower> {
        val sessions = ArrayList<Follower>()
        var base = 0
        var last = -1
        for (line in javaClass.getResourceAsStream("/recite/$name")!!.bufferedReader().readLines()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val parts = line.split(" ", limit = 4)
            when (parts[1]) {
                "session" -> { sessions += Follower(imran); base = 0; last = -1 }
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
    fun theRecogniserIsPrimedWithWhatCameBefore() {
        val f = Follower(imran)
        // Before Alif Lām Mīm: the basmala.
        assertEquals("بسم الله الرحمن الرحيم", f.context(0))
        f.heard(0, "الم الله لا اله الا هو الحي القيوم", final = false)
        // Later readings of the same utterance keep the text before it.
        assertEquals("بسم الله الرحمن الرحيم", f.context(0))
        f.heard(0, "الم الله لا اله الا هو الحي القيوم", final = true)
        // The next one: the basmala, then 3:1-2 in ordinary spelling (ٱ and the small alif as ا).
        val next = f.context(1).split(' ')
        val said = imran.take(2).flatMap { it.words }
        assertEquals(4 + said.size, next.size)
        assertEquals(said.map(Arabic::skeleton), next.drop(4).map(Arabic::skeleton))
        assertTrue(next.none { 'ٱ' in it || 'ٰ' in it })
    }

    @Test
    fun readingsAfterTheMicrophoneStartsAgainAreFollowed() {
        val f = replay("owner-1.8.2-imran.log")[1]
        // After the restart: الم, then والله لا اله الحي القيوم (3:2) and نزل عن … (3:3's start)
        // were heard; the text went on (the first try at 3:2 had been lost).
        assertTrue("position ${f.tracker.position}", f.tracker.position >= 8)
    }
}
