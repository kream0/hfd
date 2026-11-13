package app.hfd.core.recite

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MuffledWarningTest {
    @Test
    fun aMuffledVoiceThatIsFollowedIsntPointedOut() {
        // The owner's session of 2 Oct (1.14.1, phone microphone): every utterance muffled, 3:1–3:9 followed.
        val w = MuffledWarning()
        val seconds = listOf(3.6f, 6.6f, 10.9f, 7.7f, 10.5f, 5.1f, 1.3f, 10.5f, 7.5f, 6.7f)
        val moved = listOf(1, 7, 11, 6, 8, 4, 2, 9, 7, 6)
        for (i in moved.indices) assertFalse("utterance $i", w.heard(muffled = true, seconds[i], moved[i]))
    }

    @Test
    fun aMuffledVoiceThatIsntFollowedIsPointedOutUntilItIs() {
        // The Recite bench's phone in a pocket (3:1–3:4): guided, a few words follow in half a minute.
        val w = MuffledWarning()
        assertTrue(w.heard(muffled = true, 15.7f, 1))
        assertTrue(w.heard(muffled = true, 18.7f, 7))
        assertTrue(w.heard(muffled = true, 12.8f, 0))
        // Taken out of the pocket: once the text follows, it goes.
        w.heard(muffled = true, 7f, 8)
        assertFalse(w.heard(muffled = true, 8f, 9))
        assertFalse(w.shown)
    }

    @Test
    fun shortSoundsAndAClearVoiceDontCount() {
        val w = MuffledWarning()
        // A cough, a breath: a few seconds in all.
        repeat(5) { assertFalse(w.heard(muffled = true, 0.8f, 0)) }
        // A clear voice the text doesn't follow (another passage, a wrong start) isn't a microphone problem.
        repeat(5) { assertFalse(w.heard(muffled = false, 4f, 0)) }
    }

    @Test
    fun theRoomsNoiseWhileTheReciterThinksSaysNothing() {
        // The owner in a car (13 Nov): a few words, then seconds of thought with the car's noise
        // cut into utterances the recogniser writes scraps of.
        val w = MuffledWarning()
        assertFalse(w.heard(muffled = true, 4f, 3, symbols = 40, sure = 0.93f))
        repeat(6) { assertFalse(w.heard(muffled = true, 8f, 0, symbols = 6, sure = 0.4f)) }
        // The same without knowing how sure the recogniser is (Whisper).
        val older = MuffledWarning()
        repeat(6) { assertFalse(older.heard(muffled = true, 8f, 0, symbols = 6)) }
    }

    @Test
    fun aVoiceReadSurelyIsHeardWhateverItSays() {
        // Not the text (another passage, a wrong start, hesitations): read surely, the microphone hears.
        val w = MuffledWarning()
        repeat(8) { assertFalse(w.heard(muffled = true, 5f, 0, symbols = 30, sure = 0.9f)) }
        // A voice the recogniser can't make out, and the text doesn't follow: pointed out.
        assertFalse(w.heard(muffled = true, 6f, 0, symbols = 20, sure = 0.45f))
        assertTrue(w.heard(muffled = true, 7f, 0, symbols = 24, sure = 0.5f))
        // One utterance read surely, and it goes.
        assertFalse(w.heard(muffled = true, 4f, 0, symbols = 30, sure = 0.92f))
        assertFalse(w.shown)
    }
}
