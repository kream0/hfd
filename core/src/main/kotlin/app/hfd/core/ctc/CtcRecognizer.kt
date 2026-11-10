package app.hfd.core.ctc

/**
 * Reads an utterance with a CTC model, in the terms the recitation's follower speaks
 * ([app.hfd.core.recite.Follower]): the text heard, or, asked to check a text ("?\n" then the
 * words), the text heard, a tab, and what holding each word to the sound costs.
 */
class CtcRecognizer(private val model: CtcModel, private val vocabulary: CtcVocabulary = CtcVocabulary.QURAN) {
    private val mel = Mel()

    /** The model itself: a spectrogram in, each frame's symbols out. One call at a time. */
    fun interface CtcModel {
        fun run(features: Mel.Features): CtcOutput
    }

    /** What the model says of [pcm] (16 kHz, −1…1), or null for less than a tenth of a second. */
    fun listen(pcm: FloatArray): CtcOutput? = if (pcm.size < MIN_SAMPLES) null else model.run(mel.features(pcm))

    /**
     * [expected] empty or a list of continuations: the text read freely (a CTC model has no
     * decoder to steer). "?\n<words>": that text held to the sound, each word's cost after a tab.
     */
    fun transcribe(pcm: FloatArray, expected: String = ""): String {
        val out = listen(pcm)
        val text = out?.let { Ctc.greedy(it, vocabulary) }.orEmpty().replace('\n', ' ')
        if (!expected.startsWith(CHECK)) return text
        val words = expected.substring(CHECK.length).split(' ').filter { it.isNotEmpty() }
        val forced = out?.let { Ctc.forced(it, vocabulary, words.joinToString(" ")) }
        val costs = List(words.size) { w -> if (forced == null || forced.from[w] < 0) FAILED else forced.worst[w] * COST_SCALE }
        return text + "\t" + costs.joinToString(",") { String.format(java.util.Locale.US, "%.2f", it) }
    }

    companion object {
        private const val CHECK = "?\n"
        private const val MIN_SAMPLES = 1600
        /** A word no frame could be given to: as the follower's CHECK_FAILED. */
        const val FAILED = 99f
        /**
         * A word's cost is its worst character's loss (each character at its best frame, against
         * the frame's likeliest symbol), on the scale of the follower's tolerance (3): a word passes
         * with no character over [WORST_CHARACTER] nats. Measured on the bench's āyāt (10 Nov,
         * tools/ctc/coststudy.py, 2,101 words said, 624 replaced by another, 5 swapped in the
         * audio): of the words said 99 % cost under 5.8 and the swapped ones 8.4 to 14; at 7,
         * 99.5 % of the words said pass, no swapped one, 8 % of the random others. The mean loss
         * per frame, tried first, let 3 of the 5 swapped words pass where 99.5 % of the said did:
         * a long vowel's frames dilute the two letters that differ (يُنفِقُونَ said يُؤْمِنُونَ).
         */
        const val WORST_CHARACTER = 7f
        const val COST_SCALE = 3f / WORST_CHARACTER
    }
}
