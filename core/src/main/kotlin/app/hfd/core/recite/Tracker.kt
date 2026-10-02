package app.hfd.core.recite

import app.hfd.core.quran.AyahRef
import app.hfd.core.srs.Rating

/** How one word of the text was recited. */
enum class WordStatus { PENDING, OK, WRONG, MISSED, HINTED }

/** An āya to recite, with its words as written. */
data class ReciteTarget(val ref: AyahRef, val words: List<String>)

/** One āya recited to its end: which words went wrong, and the rating that follows. */
data class AyahResult(
    val ref: AyahRef,
    val words: Int,
    /** Indices (within the āya) of words recited wrongly, skipped or shown as a hint. */
    val mistakes: List<Int>,
) {
    val accuracy: Float get() = if (words == 0) 1f else (words - mistakes.size).toFloat() / words
    val rating: Rating get() = Tracker.ratingFor(words, mistakes.size)
}

/**
 * Follows a recitation of [targets] (one or more āyāt, in order) through the successive chunks
 * of speech the recogniser returns. Each chunk is aligned, letter by letter on the skeleton,
 * against the words expected next; words it covers become OK, WRONG or MISSED, and the position
 * moves on. Letter-level alignment copes with the recogniser splitting or merging words
 * differently from the Uthmani text (يَـٰٓأَيُّهَا / يا أيها). A chunk may also start further on
 * (words the recogniser dropped, or the reciter skipped): the words passed over are MISSED, which
 * a clear match allows, so the tracker never stays stuck behind the reciter. Or a little before:
 * a reciter often says the last words again before going on, and those change nothing. Or
 * further back: the reciter starts an āya over (or the passage), and the recitation goes back
 * there with no mistake — the words after it are to be recited again.
 */
class Tracker(val targets: List<ReciteTarget>) {
    private val flat: List<String> = targets.flatMap { it.words }
    private val skel: List<String> = flat.map { Arabic.skeleton(it) }
    /** Words that are disconnected letters (الٓمٓ, حمٓ, يسٓ…). */
    private val letters: BooleanArray = BooleanArray(flat.size).also { l ->
        var at = 0
        for (t in targets) {
            if (t.words.isNotEmpty() && isLetters(t.ref)) l[at] = true
            at += t.words.size
        }
    }
    /** Flat word index where each āya starts. */
    private val starts: IntArray = IntArray(targets.size + 1).also { s ->
        for (i in targets.indices) s[i + 1] = s[i] + targets[i].words.size
    }

    val status: Array<WordStatus> = Array(flat.size) { WordStatus.PENDING }

    /** First word not recited yet (flat index); [size] when done. */
    var position: Int = 0
        private set

    val size: Int get() = flat.size
    val done: Boolean get() = position >= flat.size

    /** Āya index and word index within it of flat word [i]. */
    fun locate(i: Int): Pair<Int, Int> {
        var a = 0
        while (a + 1 < targets.size && starts[a + 1] <= i) a++
        return a to (i - starts[a])
    }

    fun statusOf(aya: Int, word: Int): WordStatus = status[starts[aya] + word]

    /** Flat index of āya [aya]'s first word. */
    fun startOf(aya: Int): Int = starts[aya]

    /** Words [from] until [to] (flat indices) as written, space-separated. */
    fun text(from: Int, to: Int): String = flat.subList(from, to).joinToString(" ")

    /** Āyāt finished so far (all their words decided), as results. */
    fun finished(): List<AyahResult> = targets.indices.filter { starts[it + 1] <= position && targets[it].words.isNotEmpty() }.map(::resultOf)

    fun resultOf(aya: Int): AyahResult {
        val t = targets[aya]
        val mistakes = t.words.indices.filter { status[starts[aya] + it].let { s -> s == WordStatus.WRONG || s == WordStatus.MISSED || s == WordStatus.HINTED } }
        return AyahResult(t.ref, t.words.size, mistakes)
    }

    /** Where the recitation stands, to come back to ([reset]). */
    class Mark internal constructor(internal val position: Int, internal val status: Array<WordStatus>) {
        internal fun statusAt(w: Int): WordStatus = status[w]
    }

    fun mark(): Mark = Mark(position, status.copyOf())

    fun reset(to: Mark) {
        position = to.position
        to.status.copyInto(status)
    }

    /** The reciter asked for the next word: it counts as a mistake and the position moves on. */
    fun hint(): String? {
        if (done) return null
        status[position] = WordStatus.HINTED
        return flat[position++]
    }

    /**
     * Takes a chunk of recognised speech. Returns the number of words it moved on by (0 when the
     * chunk didn't match the text well enough to follow it: noise, or another passage; less
     * than 0 when the reciter went back).
     */
    fun feed(heard: String): Int {
        val hWords = heard.split(Regex("\\s+")).map { Arabic.skeleton(it) }.filter { it.isNotEmpty() }
        if (hWords.isEmpty()) return 0
        if (!done) {
            val moved = align(hWords)
            if (moved > 0) return moved
            // The disconnected letters (الٓمٓ, حمٓ…) are recited as long held notes, which the model
            // doesn't hear as words (it makes up منذر, فرق…): whatever is heard while they're next
            // counts as them, and the rest of the chunk may be the words after.
            if (letters[position]) {
                status[position++] = WordStatus.OK
                return 1 + if (done) 0 else maxOf(0, align(hWords))
            }
        }
        return back(hWords)
    }

    /**
     * The chunk starts with words already recited, said again: a phrase repeated, an āya or the
     * passage started over (and maybe on past where the recitation was). It goes back there:
     * those words take the status they have now (a correction counts), and the words after the
     * chunk are pending again, not mistakes. Takes a clear match (a scrap may be anywhere).
     */
    private fun back(hWords: List<String>): Int {
        if (position == 0) return 0
        val h = hWords.joinToString("")
        val e = StringBuilder()
        val owner = ArrayList<Int>()
        val end = minOf(flat.size, position + maxOf(MIN_WINDOW, hWords.size * 3))
        for (w in 0 until end) {
            e.append(skel[w])
            repeat(skel[w].length) { owner += w }
        }
        val n = e.length
        val m = h.length
        if (n == 0 || m == 0) return 0
        // The whole chunk against any stretch of what was recited (starting anywhere for free).
        val d = Array(n + 1) { IntArray(m + 1) }
        for (j in 0..m) d[0][j] = -j * GAP
        for (i in 1..n) {
            d[i][0] = 0
            for (j in 1..m) {
                val diag = d[i - 1][j - 1] + if (e[i - 1] == h[j - 1]) MATCH else -MISMATCH
                d[i][j] = maxOf(diag, d[i - 1][j] - GAP, d[i][j - 1] - GAP)
            }
        }
        // The best end; on a tie the latest (the nearest place to go back to).
        var best = 1
        for (i in 1..n) if (d[i][m] >= d[best][m]) best = i
        val matched = BooleanArray(n)
        val touched = BooleanArray(n)
        var i = best
        var j = m
        var same = 0
        while (i > 0 && j > 0) {
            val eq = e[i - 1] == h[j - 1]
            when {
                d[i][j] == d[i - 1][j - 1] + (if (eq) MATCH else -MISMATCH) -> {
                    touched[i - 1] = true
                    if (eq) { matched[i - 1] = true; same++ }
                    i--; j--
                }
                d[i][j] == d[i - 1][j] - GAP -> i--
                else -> j--
            }
        }
        val start = i
        val span = best - start
        if (span <= 0) return 0
        val first = owner[start]
        val lastWord = owner[best - 1]
        // Starting at the position or after is going on, not back (align's to judge).
        if (first >= position) return 0
        // The last words said again (a correction) take less than going far back.
        val least = if (lastWord >= position - BACK_WORDS) JUMP_MIN_LETTERS else BACK_MIN_LETTERS
        if (same < least || same < JUMP_MATCH_FRACTION * m || same < JUMP_MATCH_FRACTION * span) return 0
        for (w in first..lastWord) status[w] = statusOf(owner.indices.filter { owner[it] == w }, matched, touched, w)
        for (w in lastWord + 1 until flat.size) status[w] = WordStatus.PENDING
        val moved = lastWord + 1 - position
        position = lastWord + 1
        return moved
    }

    private fun statusOf(idx: List<Int>, matched: BooleanArray, touched: BooleanArray, w: Int): WordStatus {
        val hits = idx.count { matched[it] }
        return when {
            idx.isEmpty() || letters[w] -> WordStatus.OK
            hits == idx.size || (idx.size == 3 && hits == 2) || (idx.size >= 4 && hits >= idx.size * OK_FRACTION) -> WordStatus.OK
            idx.any { touched[it] } -> WordStatus.WRONG
            else -> WordStatus.MISSED
        }
    }

    private fun align(hWords: List<String>): Int {
        val h = hWords.joinToString("")
        val end = minOf(flat.size, position + maxOf(MIN_WINDOW, hWords.size * 3) + SKIP_WORDS)
        val e = StringBuilder()
        val owner = ArrayList<Int>()
        // The last words recited too, for a chunk that starts by saying them again.
        for (w in maxOf(0, position - BACK_WORDS) until end) {
            e.append(skel[w])
            repeat(skel[w].length) { owner += w }
        }
        val n = e.length
        val m = h.length
        /** Letters of words already recited, before the position. */
        val p = owner.indexOfFirst { it >= position }.let { if (it < 0) n else it }
        // Semi-global alignment scored like Needleman-Wunsch: the whole chunk against a stretch of
        // the expected text from the position. Matches earn more than gaps cost, so a skipped word
        // shows up as a gap rather than the chunk being cut short against a wrong word; passing
        // over words before the chunk's start costs a quarter of a gap (nothing for those already
        // recited).
        val d = Array(n + 1) { IntArray(m + 1) }
        for (j in 0..m) d[0][j] = -j * GAP
        for (i in 1..n) {
            d[i][0] = d[i - 1][0] - if (i - 1 < p) 0 else SKIP
            for (j in 1..m) {
                val diag = d[i - 1][j - 1] + if (e[i - 1] == h[j - 1]) MATCH else -MISMATCH
                d[i][j] = maxOf(diag, d[i - 1][j] - GAP, d[i][j - 1] - GAP)
            }
        }
        // The best end among the words not recited yet; on a tie the fewest words, never running
        // ahead of what was recited. A chunk that ends clearly better among the words already
        // recited (by a letter at least) only said them again: it doesn't move. Hardly better
        // isn't enough: al-Fātiḥa's 1:3 (ٱلرَّحْمَٰنِ ٱلرَّحِيمِ) is also 1:1's end, and heard with a
        // letter wrong after 1:2 it is 1:3 (Recite bench, 2 Oct).
        var best = -1
        for (i in p + 1..n) if (best < 0 || d[i][m] > d[best][m]) best = i
        var again = d[0][m]
        for (i in 1..p) again = maxOf(again, d[i][m])
        if (best < 0 || d[best][m] + MATCH <= again) return 0
        // Trace back: which expected letters were matched, substituted, or skipped.
        val matched = BooleanArray(n)
        val touched = BooleanArray(n)
        var i = best
        var j = m
        var same = 0
        while (i > 0 && j > 0) {
            val eq = e[i - 1] == h[j - 1]
            when {
                d[i][j] == d[i - 1][j - 1] + (if (eq) MATCH else -MISMATCH) -> {
                    touched[i - 1] = true
                    if (eq) { matched[i - 1] = true; same++ }
                    i--; j--
                }
                d[i][j] == d[i - 1][j] - GAP -> i--
                else -> j--
            }
        }
        // Where the chunk starts among the words not recited yet (letters before it passed over).
        val start = maxOf(if (j == 0) i else 0, p)
        val span = best - start
        val ahead = (start until best).count { matched[it] }
        // Too little in common, either way: not this text, or scraps the recogniser half heard
        // (a four-letter word sharing two letters with the next words isn't them). Don't move.
        if (span <= 0 || same < MIN_MATCH_FRACTION * m || ahead < MIN_MATCH_FRACTION * span) return 0
        // Passing over words takes a clear match, as long as what it passes over (up to a phrase).
        val passed = owner[start] - position
        val enough = (start - p).coerceIn(JUMP_MIN_LETTERS, JUMP_ENOUGH_LETTERS)
        if (passed >= 2 && (same < JUMP_MATCH_FRACTION * m || ahead < JUMP_MATCH_FRACTION * span || ahead < enough)) return 0
        // Words up to the last one the chunk reached (a word cut at the end counts whole).
        val lastWord = owner[best - 1]
        val now = (position..lastWord).map { w -> statusOf(owner.indices.filter { owner[it] == w }, matched, touched, w) }
        // Not one of them right: a breath or a scrap, not the reciter going on (أَهْ, a breath at
        // 3:5's start, matched اللَّهَ's last letter and lit إِنَّ اللَّهَ two seconds early; the
        // owner's session of 2 Oct).
        if (WordStatus.OK !in now) return 0
        for (w in position..lastWord) status[w] = now[w - position]
        val moved = lastWord + 1 - position
        position = lastWord + 1
        return moved
    }

    companion object {
        /** Sūras opening with disconnected letters (and ash-Shūrā's second āya, عسٓقٓ). */
        private val LETTER_SURAS = setOf(2, 3, 7, 10, 11, 12, 13, 14, 15, 19, 20, 26, 27, 28, 29, 30, 31, 32, 36, 38, 40, 41, 42, 43, 44, 45, 46, 50, 68)

        /** Whether āya [ref] opens with disconnected letters. */
        fun isLetters(ref: AyahRef): Boolean = (ref.aya == 1 && ref.sura in LETTER_SURAS) || (ref.sura == 42 && ref.aya == 2)

        /** Words looked at beyond the current position, at least. */
        const val MIN_WINDOW = 12
        /** Words that may be passed over before a chunk (a dropped or skipped āya). */
        const val SKIP_WORDS = 40
        /** Words already recited that a chunk may say again first. */
        const val BACK_WORDS = 8
        /** Letters a chunk must match to take the recitation further back than [BACK_WORDS]. */
        const val BACK_MIN_LETTERS = 8
        /** Share of the chunk's letters, and of the text's letters it spans, that must match for it to be followed. */
        const val MIN_MATCH_FRACTION = 0.6
        /**
         * The same, when the chunk passes over two words or more; and as many letters as it
         * passes over, at least [JUMP_MIN_LETTERS], at most [JUMP_ENOUGH_LETTERS] (a skipped āya).
         */
        const val JUMP_MATCH_FRACTION = 0.75
        const val JUMP_MIN_LETTERS = 4
        const val JUMP_ENOUGH_LETTERS = 16
        /**
         * Share of a (long) word's letters that must match for it to count as right; a
         * three-letter word may have one wrong. At the phone's level the model often mishears a
         * letter (وإيات for وإياك): that isn't the reciter's mistake. A different word is.
         */
        const val OK_FRACTION = 0.75
        private const val MATCH = 8
        private const val MISMATCH = 4
        private const val GAP = 4
        private const val SKIP = 1

        /** No mistake: Good; up to one in ten (at least one): Hard; more: Again. */
        fun ratingFor(words: Int, mistakes: Int): Rating = when {
            mistakes == 0 -> Rating.GOOD
            mistakes <= maxOf(1, words / 10) -> Rating.HARD
            else -> Rating.AGAIN
        }
    }
}
