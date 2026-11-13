package app.hfd.core.ctc

/**
 * Reads an utterance with a CTC model, in the terms the recitation's follower speaks
 * ([app.hfd.core.recite.Follower]): the text heard, or, asked to check a text ("?\n" then the
 * words), the text heard, a tab, and what holding each word to the sound costs; or, asked where a
 * text lies in time ("@\n" then the text), the frame of each of its characters; or, asked how a
 * text read right was pronounced ("~\n" then the words), each word's worst character and its cost.
 */
class CtcRecognizer(private val model: CtcModel, private val vocabulary: CtcVocabulary = CtcVocabulary.QURAN) {
    private val mel = Mel()
    private var lastPcm: FloatArray? = null
    private var lastOut: CtcOutput? = null

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
        // (The same sound asked about twice, to check its words then to time them, is listened to once.)
        val out = if (pcm === lastPcm) lastOut else listen(pcm).also { lastPcm = pcm; lastOut = it }
        val text = out?.let { Ctc.greedy(it, vocabulary) }.orEmpty().replace('\n', ' ')
        if (expected.startsWith(TIMES)) {
            // "@\n<text>": the frame each of the text's characters is written at (a frame is 40 ms).
            val placed = out?.let { Ctc.placed(it, vocabulary, expected.substring(TIMES.length)) } ?: return text
            return text + "\t" + out.frames + ";" + placed.joinToString(",")
        }
        if (expected.startsWith(SOUNDS)) {
            // "~\n<words>": per word, its worst character's cost (nats) and where in the word it is.
            val words = expected.substring(SOUNDS.length).split(' ').filter { it.isNotEmpty() }
            val forced = out?.let { Ctc.forced(it, vocabulary, words.joinToString(" ")) } ?: return text
            return text + "\t" + words.indices.joinToString(",") { w -> String.format(java.util.Locale.US, "%.2f:%d", forced.worst[w], forced.worstAt[w]) }
        }
        if (!expected.startsWith(CHECK)) return text
        val words = expected.substring(CHECK.length).split(' ').filter { it.isNotEmpty() }
        val forced = out?.let { Ctc.forced(it, vocabulary, words.joinToString(" ")) }
        val costs = List(words.size) { w -> if (forced == null || forced.from[w] < 0) FAILED else forced.worst[w] * COST_SCALE }
        return text + "\t" + costs.joinToString(",") { String.format(java.util.Locale.US, "%.2f", it) }
    }

    companion object {
        private const val CHECK = "?\n"

        /** Asked where a text's letters lie in time ([app.hfd.core.tajweed.TajweedCheck]). */
        const val TIMES = "@\n"

        /** The frames' count and each character's frame, from a reading asked with [TIMES]; null without them. */
        fun times(reading: String): Pair<Int, IntArray>? {
            val tail = reading.substringAfterLast('\t', "").takeIf { ';' in it } ?: return null
            val frames = tail.substringBefore(';').toIntOrNull() ?: return null
            val placed = tail.substringAfter(';').split(',').map { it.toIntOrNull() ?: return null }
            return frames to placed.toIntArray()
        }

        /**
         * Asked how a text the follower read right was pronounced: a word the letters were found
         * in may still have one of them, or a short vowel, that the sound doesn't bear out.
         */
        const val SOUNDS = "~\n"

        /** Each word's worst character, from a reading asked with [SOUNDS]: its cost (nats) and its place in the word; null without them. */
        fun sounds(reading: String): List<Pair<Float, Int>>? {
            val tail = reading.substringAfterLast('\t', "").takeIf { ':' in it } ?: return null
            return tail.split(',').map { item ->
                (item.substringBefore(':').toFloatOrNull() ?: return null) to (item.substringAfter(':').toIntOrNull() ?: return null)
            }
        }

        /**
         * Nats over which a word's worst character wasn't said as written ([SOUNDS]). Measured on
         * the bench's āyāt said as the text is (13 Nov, tools/ctc/vowelstudy.py, 1,533 words; the
         * text changed, not the voice): of the words said 99 % cost under 4.8 and 0.13 % over 8;
         * with one short vowel written as another the word costs over 8 for 91.5 %, with one
         * letter as another for 94.3 %.
         */
        const val UNCLEAR = 8f
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
