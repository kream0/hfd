package app.hfd.core.recite

/**
 * A pause inside a word cuts it in two: the utterance before ends on its first sounds, the next
 * one begins with the rest, and neither says it. (Ḥuṣarī holds a doubled letter through the
 * breath: «وَأَنزَلَ ٱلتَّوْرَىٰةَ» comes as "وَأَنزَلَت", then "ٱتَّوْرَىىٰةَ"; a learner begins a word, stops, and
 * says it.) The recitation waits at such a word, and a reciter who said it was held up there.
 *
 * So what a whole utterance ends on after the last word read right ([Follower.edge]) is kept, and
 * goes before the sound of the next utterance: the recogniser hears the word whole, and so does
 * the check. Only a word's worth ([MAX_SAMPLES]), and only into the utterance that comes next
 * with the recitation still where it was left.
 */
class Carry {
    private var kept: FloatArray? = null
    private var word = -1
    private var into = -1

    /** Seconds kept for the next utterance (0: nothing). */
    val seconds: Float get() = (kept?.size ?: 0) / Segmenter.RATE.toFloat()

    /**
     * Whole utterance [pcm] (as the recogniser heard it: with what was kept before it) ended, from
     * sample [from] on, on sound that isn't a word of the text yet; the recitation is left at
     * [word]. More than a word's worth isn't a word cut short: nothing is kept.
     */
    fun keep(pcm: FloatArray, from: Int, word: Int) {
        clear()
        val start = from.coerceIn(0, pcm.size)
        if (pcm.size - start !in MIN_SAMPLES..MAX_SAMPLES) return
        kept = pcm.copyOfRange(start, pcm.size)
        this.word = word
    }

    /**
     * The same from what the recogniser wrote of the utterance ([reading], each character first
     * written at frame [placed], of [frames]) and what it said of the text ([said]: the words
     * followed, as written; [Follower.edge]): the sound from the first letter written after
     * those words' letters. Nothing when it wrote nothing after them.
     */
    fun keep(pcm: FloatArray, reading: String, placed: IntArray, frames: Int, said: String, word: Int) {
        clear()
        if (frames <= 0 || placed.size != reading.length) return
        val at = after(reading, said)
        if (at >= reading.length) return
        // (A character is written a little into its sound.)
        val before = if (at > 0) placed[at - 1] + 1 else 0
        val frame = maxOf(placed[at] - LEAD_FRAMES, minOf(before, placed[at]))
        keep(pcm, (frame.toLong() * pcm.size / frames).toInt(), word)
    }

    fun clear() {
        kept = null
        word = -1
        into = -1
    }

    /**
     * The sound of utterance [id] for the recogniser ([pcm], so far or whole), after what was kept
     * for it. [position]: where the recitation stands as the utterance's first reading comes.
     */
    fun join(id: Int, pcm: FloatArray, position: Int): FloatArray {
        val k = kept ?: return pcm
        if (into != id) {
            // Kept for the utterance before this one, or the recitation has moved since (a hint, another place).
            if (into >= 0 || position != word) {
                clear()
                return pcm
            }
            into = id
        }
        return k + pcm
    }

    companion object {
        /**
         * Where in [reading] the first letter after [said]'s letters is (its length: none): the
         * beginning of [reading] whose letters are nearest [said]'s (edits), the longest on a tie.
         */
        fun after(reading: String, said: String): Int {
            val index = ArrayList<Int>()
            val h = StringBuilder()
            for ((i, c) in reading.withIndex()) {
                val l = Arabic.skeleton(c.toString())
                if (l.isNotEmpty()) { h.append(l); index += i }
            }
            val x = Arabic.skeleton(said)
            val m = h.length
            // Edits turning [said]'s letters into each beginning of the reading's.
            var row = IntArray(m + 1) { it }
            for (i in 1..x.length) {
                val next = IntArray(m + 1)
                next[0] = i
                for (j in 1..m) next[j] = minOf(row[j - 1] + if (x[i - 1] == h[j - 1]) 0 else 1, row[j] + 1, next[j - 1] + 1)
                row = next
            }
            var best = 0
            for (j in 0..m) if (row[j] <= row[best]) best = j
            if (x.isEmpty()) best = 0
            if (best >= m) return reading.length
            // From the written word's first character (an alif before the letter, not the signs of the letter before).
            var at = index[best]
            val floor = if (best > 0) index[best - 1] + 1 else 0
            while (at > floor && Arabic.skeleton(reading[at - 1].toString()).isEmpty() && !Character.isWhitespace(reading[at - 1]) &&
                Character.getType(reading[at - 1]) != Character.NON_SPACING_MARK.toInt() && reading[at - 1] != '\u0640'
            ) at--
            return at
        }

        /** Frames before the one a character is written at, where its sound may begin. */
        private const val LEAD_FRAMES = 2
        /** The longest sound kept: a word begun, and the pause's own beginning. */
        const val MAX_SAMPLES = 2 * Segmenter.RATE
        /** Less is no sound of a word: the silence an utterance ends with. */
        const val MIN_SAMPLES = Segmenter.RATE / 5
    }
}
