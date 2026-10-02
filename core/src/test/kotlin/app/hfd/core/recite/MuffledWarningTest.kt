package app.hfd.core.recite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MuffledWarningTest {
    @Test
    fun aMuffledVoiceThatIsFollowedIsntPointedOut() {
        // The owner's session of 2 Oct (1.14.1, phone microphone): every utterance muffled, 3:1–3:6 followed.
        val w = MuffledWarning()
        val moved = listOf(1, 7, 11, 6, 3, 4, 2, 0, 7, 6)
        val seconds = listOf(3.6f, 6.6f, 10.9f, 7.7f, 6.0f, 5.1f, 1.3f, 6.2f, 7.5f, 6.7f)
        for (i in moved.indices) assertFalse("utterance $i", w.heard(muffled = true, seconds[i], moved[i]))
    }

    @Test
    fun aMuffledVoiceThatIsntFollowedIsPointedOutUntilItIs() {
        // A phone in a pocket: nothing follows.
        val w = MuffledWarning()
        assertFalse(w.heard(muffled = true, 4f, 0))
        assertTrue(w.heard(muffled = true, 5f, 0))
        assertTrue(w.shown)
        // Taken out of the pocket: as soon as the text follows, it goes.
        assertFalse(w.heard(muffled = true, 4f, 3))
        assertFalse(w.heard(muffled = false, 4f, 0))
    }

    @Test
    fun shortSoundsAndAClearVoiceDontCount() {
        val w = MuffledWarning()
        // A cough, a breath: under a second and a half.
        repeat(5) { assertFalse(w.heard(muffled = true, 0.8f, 0)) }
        // A clear voice the text doesn't follow (another passage, a wrong start) isn't a microphone problem.
        repeat(3) { assertFalse(w.heard(muffled = false, 4f, 0)) }
        assertEquals(false, w.shown)
    }
}
