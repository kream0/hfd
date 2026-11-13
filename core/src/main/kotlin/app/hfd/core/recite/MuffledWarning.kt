package app.hfd.core.recite

/**
 * When to point out a muffled microphone: the voice is muffled ([Clarity.isMuffled]) and the
 * text hardly follows, under a word for every [SECONDS_PER_WORD] seconds of the last utterances
 * ([WINDOW_SECONDS], [MIN_SECONDS] at least). A muffled voice alone isn't enough: the owner's phone
 * microphone always sounds muffled (about 1 % of the energy above 1 kHz), and recognition guided
 * by the passage reads it well (2 Oct, 3:1–3:9 at 0.9 words a second), so the warning was a false
 * alarm there. A phone in a pocket (Recite bench) follows a few words, guided, in half a minute.
 *
 * Nor is a text that doesn't follow: the owner reciting in a car what they hardly know yet pauses
 * for seconds at a time, and was told of a muffled microphone (13 Nov). Two things are not a
 * voice heard badly. The room: the car's noise makes utterances of its own while the reciter
 * thinks, in which the recogniser writes next to nothing (under [VOICE_SYMBOLS] a second; a voice
 * gives 3 to 13): they say nothing either way. And a voice the recogniser is sure of
 * ([app.hfd.core.ctc.CtcOutput.sure] at [SURE] or more: any voice it reads, the text's or not): the
 * microphone hears, whatever the reason the text isn't followed, and what was counted is forgotten.
 */
class MuffledWarning {
    /** The last whole utterances: seconds, and words the text moved on by. */
    private val recent = ArrayDeque<Pair<Float, Int>>()

    /** Whether the warning shows. */
    var shown = false
        private set

    /**
     * A whole utterance of [seconds], [muffled] or not, that moved the text on by [moved] words;
     * the recogniser wrote [symbols] characters of it (letters and signs), and was [sure] of them
     * (null: a recogniser that doesn't say, Whisper).
     */
    fun heard(muffled: Boolean, seconds: Float, moved: Int, symbols: Int = Int.MAX_VALUE, sure: Float? = null): Boolean {
        // The room's noise: no voice to judge the microphone by.
        if (symbols < VOICE_SYMBOLS * seconds) return shown
        // Heard and read surely: the microphone is fine.
        if (sure != null && sure >= SURE) {
            recent.clear()
            shown = false
            return false
        }
        recent.addLast(seconds to maxOf(0, moved))
        while (recent.size > 1 && recent.sumOf { it.first.toDouble() } - recent.first().first >= WINDOW_SECONDS) recent.removeFirst()
        val total = recent.sumOf { it.first.toDouble() }
        val words = recent.sumOf { it.second }
        shown = muffled && total >= MIN_SECONDS && words * SECONDS_PER_WORD < total
        return shown
    }

    companion object {
        const val WINDOW_SECONDS = 30.0
        const val MIN_SECONDS = 12.0
        const val SECONDS_PER_WORD = 4.0
        /**
         * Characters a second the recogniser writes of a voice, at least; and how sure it is of a
         * voice it reads. Measured on the bench's āyāt (tools/ctc/claritystudy.py): a car's noise
         * alone 0.8 a second (p90 1.8) at 0.43 (p90 0.51); a voice, clear, muffled, in a pocket or
         * in that car, 3.4 and more (p10) at 0.91–0.96 (p10 0.5–0.8); a faint voice under the car's
         * noise 3.5 at 0.50.
         */
        const val VOICE_SYMBOLS = 2.0
        const val SURE = 0.6f
    }
}
