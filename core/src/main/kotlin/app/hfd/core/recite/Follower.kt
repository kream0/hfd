package app.hfd.core.recite

/**
 * Follows a recitation through the recogniser's successive readings of each [Utterance]: a
 * partial reading moves the text on at once, and is replaced by the next reading of the same
 * utterance; the final one stays. A word heard right in two readings in a row stays right: on a
 * longer stretch the model sometimes drops words it had heard (ولم يولد, or the basmala before
 * an āya), and a later reading can't take them back.
 */
class Follower(val targets: List<ReciteTarget>) {
    val tracker = Tracker(targets)
    /** The utterance being followed, and the tracker before it. */
    private var current = -1
    private var before: Tracker.Mark? = null
    /** Utterances up to this one are done with. */
    private var closed = -1
    /** The utterance's previous reading, and the words right in it and the one before. */
    private var previous: Array<WordStatus>? = null
    private var stable = BooleanArray(tracker.size)

    /** A reading of utterance [id]; returns how far the recitation moved from before it. */
    fun heard(id: Int, text: String, final: Boolean): Int {
        if (id <= closed) return 0
        if (id != current) {
            current = id
            before = tracker.mark()
            previous = null
            stable = BooleanArray(tracker.size)
        } else {
            before?.let(tracker::reset)
        }
        val from = before?.position ?: tracker.position
        val clean = withoutOpening(text)
        tracker.feed(clean)
        val fellBack = tracker.position <= stable.indexOfLast { it }
        keepStable(from)
        // This reading lost stable words at its start: what it heard is after them.
        if (fellBack) {
            val kept = tracker.mark()
            if (tracker.feed(clean) == 0) tracker.reset(kept)
        }
        val now = tracker.status
        val prev = previous
        stable = BooleanArray(now.size) { prev != null && prev[it] == WordStatus.OK && now[it] == WordStatus.OK }
        previous = now.copyOf()
        if (final) closed = id
        return tracker.position - from
    }

    /** Words stable over the readings before stay right; the words among them keep what they were. */
    private fun keepStable(from: Int) {
        val last = stable.indexOfLast { it }
        val prev = previous
        if (last < 0 || prev == null) return
        val status = tracker.status.copyOf()
        for (w in from..last) {
            status[w] = when {
                stable[w] -> WordStatus.OK
                status[w] != WordStatus.PENDING -> status[w]
                prev[w] != WordStatus.PENDING -> prev[w]
                else -> WordStatus.MISSED
            }
        }
        tracker.reset(Tracker.Mark(maxOf(tracker.position, last + 1), status))
    }

    /**
     * The next word shown: what has been followed so far stays, and readings of utterances up to
     * [through] are ignored from now on (the recorder starts a new one).
     */
    fun hint(through: Int): String? {
        closed = maxOf(closed, through, current)
        return tracker.hint()
    }

    /**
     * [text] without the isti'ādha and, at the start of an āya, the basmala: recited before the
     * text (before a sūra, and by some before any passage) but not part of it here.
     */
    fun withoutOpening(text: String): String {
        var words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        fun startsWith(phrase: String): Int {
            val p = Arabic.words(phrase).map { Arabic.skeleton(it) }
            val w = words.take(p.size).map { Arabic.skeleton(it) }
            return if (w == p) p.size else 0
        }
        words = words.drop(startsWith(ISTIADHA))
        if (!tracker.done) {
            val (a, w) = tracker.locate(tracker.position)
            val ref = targets[a].ref
            if (w == 0 && !(ref.sura == 1 && ref.aya == 1)) words = words.drop(startsWith(BASMALA))
        }
        return words.joinToString(" ")
    }

    companion object {
        private const val ISTIADHA = "أعوذ بالله من الشيطان الرجيم"
        private const val BASMALA = "بسم الله الرحمن الرحيم"
    }
}
