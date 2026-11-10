package app.hfd.core.ctc

/**
 * What a CTC model says of an utterance: for each frame (25 a second), the log-probability of
 * each symbol; the last symbol is the blank ("nothing new here").
 */
class CtcOutput(val logProbs: FloatArray, val frames: Int, val symbols: Int) {
    init {
        require(logProbs.size >= frames * symbols) { "${logProbs.size} values for $frames × $symbols" }
    }

    val blank: Int get() = symbols - 1

    operator fun get(frame: Int, symbol: Int): Float = logProbs[frame * symbols + symbol]

    /** The likeliest symbol of each frame. */
    fun best(): IntArray = IntArray(frames) { t ->
        var arg = 0
        var top = Float.NEGATIVE_INFINITY
        val row = t * symbols
        for (s in 0 until symbols) if (logProbs[row + s] > top) { top = logProbs[row + s]; arg = s }
        arg
    }
}

/** The characters a model writes (Uthmani script with its signs), in the model's order. */
class CtcVocabulary(val characters: List<String>) {
    private val index = HashMap<Char, Int>().apply { characters.forEachIndexed { i, c -> if (c.length == 1) put(c[0], i) } }
    val blank: Int get() = characters.size
    fun indexOf(c: Char): Int? = index[c]
}

object Ctc {
    /** The text read freely: each frame's likeliest symbol, repeats and blanks dropped. */
    fun greedy(out: CtcOutput, vocabulary: CtcVocabulary): String = buildString {
        var previous = -1
        for (s in out.best()) {
            if (s != previous && s != out.blank) append(vocabulary.characters[s])
            previous = s
        }
    }

    /** How [text]'s words lie on the frames once the text is held to them, and what that costs. */
    class Forced(
        /** Per word: the mean over its frames of what its character loses to the frame's likeliest symbol (nats). */
        val mean: FloatArray,
        /** Per word: its worst character (each character at its best frame). */
        val worst: FloatArray,
        /** Per word: its first and last frame (−1 for a word with no character the model writes). */
        val from: IntArray,
        val to: IntArray,
    )

    /**
     * [text] held to the frames (Viterbi over the CTC lattice: every character in order, blanks
     * between as needed). Null when the utterance has fewer frames than the text needs.
     */
    fun forced(out: CtcOutput, vocabulary: CtcVocabulary, text: String): Forced? {
        val words = text.split(' ').filter { it.isNotEmpty() }
        val space = vocabulary.indexOf(' ')
        val ids = ArrayList<Int>()
        val wordOf = ArrayList<Int>()
        words.forEachIndexed { w, word ->
            for (c in word) vocabulary.indexOf(c)?.let { ids += it; wordOf += w }
            if (w + 1 < words.size && space != null) { ids += space; wordOf += -1 }
        }
        val count = ids.size
        val frames = out.frames
        if (count == 0 || frames < count) return null
        // States: blank, character 0, blank, character 1, … blank.
        val states = 2 * count + 1
        val blank = out.blank
        fun symbol(state: Int) = if (state % 2 == 0) blank else ids[state / 2]
        var score = FloatArray(states) { NEG }
        var next = FloatArray(states)
        val back = ByteArray(frames * states)
        score[0] = out[0, blank]
        score[1] = out[0, ids[0]]
        for (t in 1 until frames) {
            for (s in 0 until states) {
                var best = score[s]
                var step = 0
                if (s > 0 && score[s - 1] > best) { best = score[s - 1]; step = 1 }
                if (s > 1 && s % 2 == 1 && ids[s / 2] != ids[s / 2 - 1] && score[s - 2] > best) { best = score[s - 2]; step = 2 }
                next[s] = if (best <= NEG) NEG else best + out[t, symbol(s)]
                back[t * states + s] = step.toByte()
            }
            val swap = score; score = next; next = swap
        }
        var state = if (score[states - 1] >= score[states - 2]) states - 1 else states - 2
        if (score[state] <= NEG) return null
        val n = words.size
        val loss = DoubleArray(n)
        val held = IntArray(n)
        val from = IntArray(n) { -1 }
        val to = IntArray(n) { -1 }
        val least = FloatArray(count) { Float.POSITIVE_INFINITY }
        val top = out.best()
        for (t in frames - 1 downTo 0) {
            if (state % 2 == 1) {
                val c = state / 2
                val w = wordOf[c]
                val lost = out[t, top[t]] - out[t, ids[c]]
                if (lost < least[c]) least[c] = lost
                if (w >= 0) {
                    loss[w] += lost.toDouble(); held[w]++
                    from[w] = t
                    if (to[w] < 0) to[w] = t
                }
            }
            state -= back[t * states + state]
        }
        val worst = FloatArray(n)
        for (c in 0 until count) {
            val w = wordOf[c]
            if (w >= 0 && least[c] > worst[w]) worst[w] = least[c]
        }
        return Forced(FloatArray(n) { if (held[it] == 0) 0f else (loss[it] / held[it]).toFloat() }, worst, from, to)
    }

    private const val NEG = -1e30f
}
