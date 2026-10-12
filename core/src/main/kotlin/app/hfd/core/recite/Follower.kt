package app.hfd.core.recite

/**
 * Follows a recitation through the recogniser's successive readings of each [Utterance]: a
 * partial reading moves the text on at once, and is replaced by the next reading of the same
 * utterance; the final one stays. A word heard right in two readings in a row stays right: on a
 * longer stretch the model sometimes drops words it had heard (ولم يولد, or the basmala before
 * an āya), and a later reading can't take them back. And a reading that follows nothing, after
 * one of the same utterance that did, leaves that one standing (the longer stretch misread).
 */
class Follower(val targets: List<ReciteTarget>, private val holdsEdges: Boolean = true) {
    val tracker = Tracker(targets)
    /** The utterance being followed, and the tracker before it. */
    private var current = -1
    private var before: Tracker.Mark? = null
    /** Utterances up to this one are done with. */
    private var closed = -1
    /** The utterance's previous reading, and the words right in it and the one before. */
    private var previous: Array<WordStatus>? = null
    private var stable = BooleanArray(tracker.size)
    /** Where the utterance's last reading that followed something left the recitation. */
    private var followed: Tracker.Mark? = null
    /**
     * The word the reciter's voice is on, for the marker. While they speak (a partial reading),
     * the last word heard: the next one isn't said yet (a madd held at an āya's end showed the
     * next āya's first word, the owner, 12 Oct). Once they pause (the final reading), the word to
     * say next. Also words already recited, said again before the recitation goes back there
     * (Tracker.voiceAt). Null: at the position.
     */
    var voice: Int? = null
        private set

    /** A reading of utterance [id]; returns how far the recitation moved from before it. */
    fun heard(id: Int, text: String, final: Boolean): Int {
        if (id <= closed) return 0
        if (id != current) {
            current = id
            before = tracker.mark()
            previous = null
            stable = BooleanArray(tracker.size)
            followed = null
        } else {
            before?.let(tracker::reset)
        }
        val from = before?.position ?: tracker.position
        val clean = withoutOpening(text)
        if (final) {
            val all = text.split(Regex("\\s+")).filter { it.isNotBlank() }
            finalOpening = all.take(all.size - clean.split(Regex("\\s+")).count { it.isNotBlank() })
        }
        tracker.feed(clean)
        voice = null
        if (tracker.position < from || tracker.status.indices.any { it < from && tracker.status[it] != before?.statusAt(it) }) {
            // Went back (a restart, a phrase said again): nothing of it is stable yet.
            previous = tracker.status.copyOf()
            stable = BooleanArray(tracker.size)
            // Still speaking: the marker is on the word said last (or the one the reading ends inside).
            if (final) closed = id else voice = tracker.cut ?: (tracker.position - 1).takeIf { it >= 0 }
            return tracker.position - from
        }
        // Nothing of this reading followed, where the one before did (a skipped āya accepted on a
        // clear stretch, then the final reading a little worse, 2 Oct): the one before stands.
        val last = followed
        if (tracker.position == from && last != null && last.position > from) {
            tracker.reset(last)
            // What it heard may go on from there.
            val kept = tracker.mark()
            if (tracker.feed(clean) <= 0) tracker.reset(kept)
        }
        val fellBack = tracker.position <= stable.indexOfLast { it }
        keepStable(from)
        // This reading lost stable words at its start: what it heard is after them.
        if (fellBack) {
            val kept = tracker.mark()
            if (tracker.feed(clean) == 0) tracker.reset(kept)
        }
        val now = tracker.status
        val prev = previous
        stable = BooleanArray(now.size) { it >= from && prev != null && prev[it] == WordStatus.OK && now[it] == WordStatus.OK }
        previous = now.copyOf()
        if (tracker.position > from) followed = tracker.mark()
        // Nothing followed: maybe words already recited, said again.
        if (tracker.position == from) voice = tracker.voiceAt(clean, final)
        if (final) {
            closed = id
        } else {
            // Still speaking: the voice is on the last word heard, unless it ends on one held back.
            val held = holdsEdges && holdEdge(from)
            if (voice == null && !held && tracker.position > from) voice = tracker.position - 1
        }
        return tracker.position - from
    }

    /**
     * A partial reading ends where the reciter is still speaking: the words it ends on, unless
     * right, may be one not said whole yet (a madd held: الرحمـــن read الرحار, then right; 1.15.2's
     * readings every 0.3 s turned such words red, then white, the owner, 11 Oct). They wait, not
     * yet recited, for a reading that goes past them or the utterance's end. True if any wait.
     */
    private fun holdEdge(from: Int): Boolean {
        var edge = tracker.position
        while (edge > from && tracker.status[edge - 1] != WordStatus.OK) edge--
        if (edge == tracker.position) return false
        val status = tracker.status.copyOf()
        for (w in edge until tracker.position) status[w] = WordStatus.PENDING
        tracker.reset(Tracker.Mark(edge, status))
        return true
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
     * What the reciter may be saying in utterance [id], for the recogniser to prefer among what it
     * nearly hears (app/src/main/cpp/bias.h): the text from where the utterance began; at a cost
     * (edits the audio must make up for), from a few words before (a phrase said again), from the
     * start of its āya (started over) and of the next two (skipped); at an āya's start also after
     * the isti'ādha and the basmala. One continuation per line ("<cost>\t<text>"); empty once the
     * passage is done (a recitation going back is read freely).
     */
    fun expected(id: Int): String {
        val from = (if (id == current && id > closed) before?.position else null) ?: tracker.position
        if (from >= tracker.size) return ""
        val aya = tracker.locate(from).first
        // Each start and its cost: the cheapest reason to begin there.
        val starts = sortedMapOf<Int, Int>()
        fun start(at: Int, cost: Int) { if (at in 0 until tracker.size) starts[at] = minOf(starts[at] ?: cost, cost) }
        start(from, 0)
        for (back in 1..BACK_WORDS) start(from - back, 1)
        start(tracker.startOf(aya), 1)
        if (aya + 1 < targets.size) start(tracker.startOf(aya + 1), 2)
        if (aya + 2 < targets.size) start(tracker.startOf(aya + 2), 3)
        val ayaStarts = targets.indices.map { tracker.startOf(it) }.toSet()
        val lines = ArrayList<String>()
        for ((s, cost) in starts) {
            val text = tracker.text(s, minOf(tracker.size, s + EXPECTED_WORDS))
            lines += "$cost\t$text"
            if (s in ayaStarts) {
                lines += "$cost\t$BASMALA $text"
                lines += "$cost\t$ISTIADHA $text"
                lines += "$cost\t$ISTIADHA $BASMALA $text"
            }
        }
        return lines.joinToString("\n")
    }

    /**
     * Mistakes a whole utterance left, to check before they count: words [from]–[to] of the text
     * (what it covered) and those among them WRONG or MISSED ([words]). The recogniser reads the
     * utterance again held to that text ([expected], after the isti'ādha or basmala the reciter
     * began with: [opening] words), and says what each word cost it against its own guess
     * (app/src/main/cpp/bias.h, hfd_check): [checked] takes a mistake that cost little as right.
     */
    class Check internal constructor(val id: Int, val from: Int, val to: Int, val text: String, val words: List<Int>, val opening: Int) {
        /** For the recogniser. */
        val expected: String get() = "?\n$text"

        companion object {
            /** The words' costs (nats) after the reading and a tab, or null. */
            fun costs(reading: String): List<Float>? =
                reading.substringAfterLast('\t', "").takeIf { it.isNotBlank() }?.split(',')?.map { it.trim().toFloatOrNull() ?: return null }
        }
    }

    /** The isti'ādha / basmala words the last final reading began with. */
    private var finalOpening: List<String> = emptyList()

    /** The mistakes whole utterance [id] (just followed) left in what it covered, if any. */
    fun toCheck(id: Int): Check? {
        if (id != current) return null
        val from = before?.position ?: return null
        val to = tracker.position
        if (to <= from) return null
        val bad = (from until to).filter { tracker.status[it] == WordStatus.WRONG || tracker.status[it] == WordStatus.MISSED }
        if (bad.isEmpty()) return null
        val text = (finalOpening + tracker.text(from, to)).joinToString(" ")
        return Check(id, from, to, text, bad, finalOpening.size)
    }

    /**
     * [costs], each word's cost (nats) reading [check]'s text: its mistakes that cost [tolerance]
     * or less are right (said, and misheard). Returns how many. None if the reading couldn't be
     * held to the text to its end ([CHECK_FAILED]): the utterance isn't that text (an āya skipped,
     * whose short words fit any voice: لا, إلا at 1–3 nats on the owner's 3:3, 10 Oct).
     */
    fun checked(check: Check, costs: List<Float>, tolerance: Float = CHECK_TOLERANCE): Int {
        if (costs.size != check.opening + check.to - check.from) return 0
        if (costs.any { it >= CHECK_FAILED }) return 0
        val right = check.words.filter { costs[check.opening + it - check.from] <= tolerance }
        for (w in right) tracker.status[w] = WordStatus.OK
        return right.size
    }

    /**
     * The next word shown: what has been followed so far stays, and readings of utterances up to
     * [through] are ignored from now on (the recorder starts a new one).
     */
    fun hint(through: Int): String? {
        closed = maxOf(closed, through, current)
        voice = null
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
        /**
         * Nats: what a mistake may cost the recogniser held to the text ([Check]) and still be the
         * text's (said, misheard). Read again, a word said right costs 0–5 on a dark voice, another
         * word 10 or more, or it can't be held to the text (bias.h, al-Ikhlās darkened, 10 Oct).
         */
        const val CHECK_TOLERANCE = 3f
        /** A word's cost when the reading couldn't be held to the text there (bias.h HFD_CHECK_FAIL). */
        const val CHECK_FAILED = 99f
        /** Words before the utterance's start it may begin with (said again). */
        private const val BACK_WORDS = 3
        /** An utterance holds at most 20 s of recitation: some forty words. */
        private const val EXPECTED_WORDS = 50
        private const val ISTIADHA = "أعوذ بالله من الشيطان الرجيم"
        private const val BASMALA = "بسم الله الرحمن الرحيم"
    }
}
