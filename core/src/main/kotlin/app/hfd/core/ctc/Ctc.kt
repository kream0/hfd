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

    /**
     * How sure the model is of what it writes: the mean probability of the likeliest symbol over
     * the frames it writes one in (not the blank). Null when it writes nothing. A voice it reads,
     * clear or muffled, gives 0.9 and more; a room's noise it makes scraps of, under 0.5
     * (tools/ctc/claritystudy.py, 13 Nov).
     */
    fun sure(): Float? {
        var sum = 0.0
        var n = 0
        val blank = blank
        for (t in 0 until frames) {
            var arg = 0
            var top = Float.NEGATIVE_INFINITY
            val row = t * symbols
            for (s in 0 until symbols) if (logProbs[row + s] > top) { top = logProbs[row + s]; arg = s }
            if (arg != blank) { sum += kotlin.math.exp(top.toDouble()); n++ }
        }
        return if (n == 0) null else (sum / n).toFloat()
    }
}

/** The characters a model writes (Uthmani script with its signs), in the model's order. */
class CtcVocabulary(val characters: List<String>) {
    private val index = HashMap<Char, Int>().apply { characters.forEachIndexed { i, c -> if (c.length == 1) put(c[0], i) } }
    val blank: Int get() = characters.size
    fun indexOf(c: Char): Int? = index[c]

    companion object {
        /**
         * The Uthmani script's characters as Tanzil writes them, sorted by code point: the space,
         * the letters, the vowel signs, shadda and sukūn, the small signs. The vocabulary of
         * mrmuminov/quran-fastconformer-ctc-ar (its vocab.json: CtcTest).
         */
        val QURAN = CtcVocabulary("\u0020\u0621\u0623\u0624\u0625\u0626\u0627\u0628\u0629\u062A\u062B\u062C\u062D\u062E\u062F\u0630\u0631\u0632\u0633\u0634\u0635\u0636\u0637\u0638\u0639\u063A\u0640\u0641\u0642\u0643\u0644\u0645\u0646\u0647\u0648\u0649\u064A\u064B\u064C\u064D\u064E\u064F\u0650\u0651\u0652\u0653\u0654\u0670\u0671\u06DC\u06DF\u06E0\u06E2\u06E3\u06E5\u06E6\u06E7\u06E8\u06EA\u06EB\u06EC\u06ED".map { it.toString() })
    }
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

    /** The frame each character of the text read freely ([greedy]) is first written at. */
    fun written(out: CtcOutput): IntArray {
        val at = ArrayList<Int>()
        var previous = -1
        for ((t, s) in out.best().withIndex()) {
            if (s != previous && s != out.blank) at += t
            previous = s
        }
        return at.toIntArray()
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
        /** Per word: where in it its worst character is (an index into the word as written; −1 without one). */
        val worstAt: IntArray,
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
        val placeOf = ArrayList<Int>()
        words.forEachIndexed { w, word ->
            for ((i, c) in word.withIndex()) vocabulary.indexOf(c)?.let { ids += it; wordOf += w; placeOf += i }
            if (w + 1 < words.size && space != null) { ids += space; wordOf += -1; placeOf += -1 }
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
        val worstAt = IntArray(n) { -1 }
        for (c in 0 until count) {
            val w = wordOf[c]
            if (w >= 0 && (worstAt[w] < 0 || least[c] > worst[w])) { worst[w] = least[c]; worstAt[w] = placeOf[c] }
        }
        return Forced(FloatArray(n) { if (held[it] == 0) 0f else (loss[it] / held[it]).toFloat() }, worst, from, to, worstAt)
    }

    /**
     * Where [text]'s characters lie in time when the text is held to the frames: for each character
     * of [text], the first frame it is written at (−1 for one the model doesn't write: a space
     * where it has none, a sign of pause). Null when the text doesn't fit the utterance.
     */
    fun placed(out: CtcOutput, vocabulary: CtcVocabulary, text: String): IntArray? {
        val ids = ArrayList<Int>()
        val where = ArrayList<Int>()
        for ((i, c) in text.withIndex()) vocabulary.indexOf(c)?.let { ids += it; where += i }
        val count = ids.size
        val frames = out.frames
        if (count == 0 || frames < count) return null
        val states = 2 * count + 1
        val blank = out.blank
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
                next[s] = if (best <= NEG) NEG else best + out[t, if (s % 2 == 0) blank else ids[s / 2]]
                back[t * states + s] = step.toByte()
            }
            val swap = score; score = next; next = swap
        }
        var state = if (score[states - 1] >= score[states - 2]) states - 1 else states - 2
        if (score[state] <= NEG) return null
        val placed = IntArray(text.length) { -1 }
        for (t in frames - 1 downTo 0) {
            if (state % 2 == 1) placed[where[state / 2]] = t
            state -= back[t * states + state]
        }
        return placed
    }

    private const val NEG = -1e30f
}
