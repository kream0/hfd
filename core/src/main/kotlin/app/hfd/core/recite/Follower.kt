package app.hfd.core.recite

/**
 * Follows a recitation through the recogniser's successive readings of each [Utterance]: a
 * partial reading moves the text on at once, and is replaced by the next reading of the same
 * utterance; the final one stays. A word heard right in two readings in a row stays right: on a
 * longer stretch the model sometimes drops words it had heard (ولم يولد, or the basmala before
 * an āya), and a later reading can't take them back. And a reading that follows nothing, after
 * one of the same utterance that did (or went back), leaves that one standing (the longer
 * stretch misread).
 */
class Follower(
    val targets: List<ReciteTarget>,
    private val holdsEdges: Boolean = true,
    /** A reading that follows nothing after one of the same utterance went back leaves it gone back. */
    private val keepsGoingBack: Boolean = true,
    /** The āya before is among what the reciter may be saying ([expected]): said again. */
    private val steersPrevious: Boolean = true,
    ayaStartTies: Boolean = true,
    /** A word only read right while it was the last one read isn't kept right against the whole utterance's reading ([keepStable]). */
    private val finalRules: Boolean = false,
    /** Words in a row a reading may pass over or say wrong and still be followed past them ([Tracker]). */
    skip: Int = Tracker.SKIP_WORDS,
) {
    val tracker = Tracker(targets, ayaStartTies, skip)
    /**
     * The recitation waits at a word not said right until it is said ([settle]); what the reciter
     * said after it in the same breath is to say again. The app's with the recogniser that writes
     * what it hears, at every level (whether the word is shown as a mistake is the level's).
     * Otherwise the word is marked wrong or missed and the recitation goes on.
     */
    var waits = false
    /** Words the recitation waited at ([waits]): the reciter stumbled there, whatever was said after. */
    val stumbled = BooleanArray(tracker.size)
    /** The utterance being followed, and the tracker before it. */
    private var current = -1
    private var before: Tracker.Mark? = null
    /** Utterances up to this one are done with. */
    private var closed = -1
    /** The utterance's previous reading, and the words right in it and the one before. */
    private var previous: Array<WordStatus>? = null
    private var stable = BooleanArray(tracker.size)
    /** Stable words read right at least once with a word read after them (said whole by then). */
    private var whole = BooleanArray(tracker.size)
    /** Where the utterance's last reading that followed something left the recitation. */
    private var followed: Tracker.Mark? = null
    /** Where the utterance's last reading that went back left it, and the marker then. */
    private var wentBack: Pair<Tracker.Mark, Int?>? = null
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
            // What the utterances before recited is kept (the reciter may go back, and take it up again from there).
            tracker.remember()
            before = tracker.mark()
            previous = null
            stable = BooleanArray(tracker.size)
            whole = BooleanArray(tracker.size)
            followed = null
            wentBack = null
        } else {
            before?.let(tracker::reset)
        }
        val from = before?.position ?: tracker.position
        val clean = withoutOpening(text)
        // The utterance is the isti'ādha or the basmala so far: what an earlier, rougher reading
        // of it seemed to follow in the text was their sound (بِسْمِلم, a second into the basmala).
        if (clean.isEmpty() && text.isNotBlank()) {
            followed = null
            wentBack = null
        }
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
            whole = BooleanArray(tracker.size)
            // Still speaking: the marker is on the word said last (or the one the reading ends inside).
            if (final) closed = id else voice = tracker.cut ?: (tracker.position - 1).takeIf { it >= 0 }
            followed = null
            wentBack = tracker.mark() to voice
            return tracker.position - from
        }
        // Nothing of this reading followed, after one of the same utterance went back: the going
        // back stands. (The owner saying 3:2 again on dark earbuds, 12 Oct: back at الله لا, then
        // its readings as it grew, الله إلى إلى …, matched nothing, and the text was at 3:3 again.)
        val back = wentBack
        if (keepsGoingBack && back != null && tracker.position == from) {
            tracker.reset(back.first)
            if (final) closed = id
            voice = if (final) null else back.second
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
        keepStable(from, final)
        // This reading lost stable words at its start: what it heard is after them.
        if (fellBack) {
            val kept = tracker.mark()
            if (tracker.feed(clean) == 0) tracker.reset(kept)
        }
        val now = tracker.status
        val prev = previous
        stable = BooleanArray(now.size) { it >= from && prev != null && prev[it] == WordStatus.OK && now[it] == WordStatus.OK }
        for (w in from until tracker.position - 1) if (stable[w]) whole[w] = true
        previous = now.copyOf()
        if (tracker.position > from) {
            followed = tracker.mark()
            wentBack = null
        }
        // Nothing followed: maybe words already recited, said again.
        if (tracker.position == from) voice = tracker.voiceAt(clean, final)
        if (final) {
            closed = id
        } else {
            // Still speaking: the voice is on the last word heard, unless it ends on one held back.
            var held = holdsEdges && holdEdge(from)
            // (Waiting: nothing shows past a word not right so far; the whole reading and its check decide.)
            if (waits) firstBad(from)?.let { holdAt(it); held = true }
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
        tracker.reset(Tracker.Mark(edge, status, tracker.resumed))
        return true
    }

    /**
     * The first word from [from] this utterance leaves wrong or missed. Not one right before it
     * (it stays right), nor one wrong before it as it is now (from when mistakes were marked).
     */
    private fun firstBad(from: Int): Int? {
        val b = before ?: return null
        val status = tracker.status
        return (from until tracker.position).firstOrNull { w ->
            (status[w] == WordStatus.WRONG || status[w] == WordStatus.MISSED) && b.statusAt(w) != WordStatus.OK && (w >= b.position || b.statusAt(w) != status[w])
        }
    }

    /**
     * How far the recitation shows: the position, but when it [waits], no further than a word the
     * utterance leaves wrong or missed until [settle] has ruled (its check may take a moment, and
     * the words after it would light up, then go out).
     */
    val reach: Int get() = (if (waits) firstBad(0) else null) ?: tracker.position

    /** The recitation is back at word [w], and nothing is said from there on. */
    private fun holdAt(w: Int) {
        val status = tracker.status.copyOf()
        for (k in w until status.size) status[k] = WordStatus.PENDING
        tracker.reset(Tracker.Mark(w, status, tracker.resumed))
    }

    /**
     * Whole utterance [id] is read and checked ([toCheck], [checked]): when the recitation [waits],
     * it goes back to the first word the utterance left wrong or missed, to be said again from
     * there (returned; null when every word is right, or when it doesn't wait). A word right
     * before the utterance stays right, however it was read this time.
     *
     * The words the utterance ends on, when none after them is right, are only not said yet
     * ([atEdge]): a reciter who pauses inside a phrase is cut there, sometimes inside a word
     * (Ḥuṣarī's «وَأَنزَلَ ٱلتَّوْرَىٰةَ», the doubled tāʾ begun before the breath: the first utterance ends
     * on a scrap of the word, the next one says it; the bench, 13 Nov). The recitation is at them,
     * and they are no stumble unless what is said next leaves them out.
     */
    var atEdge = false
        private set

    /** The last [settle] left a word to say again with words right after it: a stumble. */
    private var stumbledNow = false

    fun settle(id: Int): Int? {
        atEdge = false
        stumbledNow = false
        if (!waits || id != current) return null
        val b = before ?: return null
        val status = tracker.status
        for (w in 0 until tracker.position) {
            if ((status[w] == WordStatus.WRONG || status[w] == WordStatus.MISSED) && b.statusAt(w) == WordStatus.OK) status[w] = WordStatus.OK
        }
        val w = firstBad(0) ?: return null
        atEdge = w >= b.position && (w until tracker.position).none { status[it] == WordStatus.OK }
        if (!atEdge) {
            stumbled[w] = true
            stumbledNow = true
        }
        holdAt(w)
        voice = null
        return w
    }

    /**
     * Words stable over the readings before stay right; the words among them keep what they were.
     * Unless a word was only ever read right as the last one read, still being said, and the
     * whole utterance's reading ([final]) reads it differently: it then stands as wrong, for the
     * check to rule on. Two readings of a word not said whole yet, steered to the text, can both
     * give the text's word (يُنفِقُونَ twice while يُؤْمِنُونَ was being said in its place, then يُنْمِنُونَ
     * to the end: the bench's swapped word, kept right by them). A word read right with others
     * after it stays: the model misreads on a longer stretch what it had read (the owner's ٱلدِّينِ,
     * four times right, then اتِّينِ once 1:5 followed in the same utterance, 11 Oct).
     */
    private fun keepStable(from: Int, final: Boolean) {
        val last = stable.indexOfLast { it }
        val prev = previous
        if (last < 0 || prev == null) return
        val status = tracker.status.copyOf()
        for (w in from..last) {
            status[w] = when {
                stable[w] && finalRules && final && !whole[w] && status[w] == WordStatus.WRONG -> WordStatus.WRONG
                stable[w] -> WordStatus.OK
                status[w] != WordStatus.PENDING -> status[w]
                prev[w] != WordStatus.PENDING -> prev[w]
                else -> WordStatus.MISSED
            }
        }
        tracker.reset(Tracker.Mark(maxOf(tracker.position, last + 1), status, tracker.resumed))
    }

    /**
     * What the reciter may be saying in utterance [id], for the recogniser to prefer among what it
     * nearly hears (app/src/main/cpp/bias.h): the text from where the utterance began; at a cost
     * (edits the audio must make up for), from a few words before (a phrase said again), from the
     * start of its āya (started over), of the āya before (said again) and of the next two
     * (skipped); at an āya's start also after the isti'ādha and the basmala. One continuation per
     * line ("<cost>\t<text>"); empty once the passage is done (a recitation going back is read freely).
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
        // The āya just recited, said again as a learner does: as likely as starting this one over
        // (the owner's 3:2 said again, read الله إلى إلى without it, 12 Oct); from its middle, less.
        if (steersPrevious && aya > 0) start(tracker.startOf(aya - 1), if (from == tracker.startOf(aya)) 1 else 2)
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
    class Check internal constructor(
        val id: Int, val from: Int, val to: Int, val text: String, val words: List<Int>, val opening: Int,
        /**
         * Those of [words] the free reading did not hear at all, or as something else (not
         * [Tracker.nearly]; only told apart when the tracker goes letter for letter): held to
         * the sound, a word that was not said still costs little one time in ten.
         */
        val unheard: Set<Int> = emptySet(),
    ) {
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

    /**
     * What whole utterance [id] (just followed) read, when every word of it is right: words
     * [from] until [to] of the text, and the [text] said (with the isti'ādha or basmala it began
     * with: [opening] words). The timing of its long vowels can then be read on the sound
     * ([app.hfd.core.tajweed.TajweedCheck]).
     */
    class Read internal constructor(val from: Int, val to: Int, val text: String, val opening: List<String>)

    /**
     * The first word the utterance being followed said: where the recitation was, or the word it
     * was taken up again at, further on among those already recited ([Tracker.resumed]: the words
     * between were not said this time, and no sound of this utterance is theirs).
     */
    private fun began(): Int? {
        val from = before?.position ?: return null
        return tracker.resumed?.takeIf { it > from && it <= tracker.position } ?: from
    }

    fun read(id: Int): Read? {
        if (id != current) return null
        val from = began() ?: return null
        val to = tracker.position
        if (to <= from || (from until to).any { tracker.status[it] != WordStatus.OK }) return null
        return Read(from, to, (finalOpening + tracker.text(from, to)).joinToString(" "), finalOpening)
    }

    /**
     * After [settle]: what whole utterance [id] said of the text, as written (the isti'ādha or
     * basmala it began with, then the words followed up to where the recitation now is; maybe
     * nothing), when it went on or stayed. What the recogniser wrote after that is no word of the
     * text yet: the beginning of the next one, cut by the pause, which the next utterance is
     * heard with ([Carry]). Null when the recitation doesn't wait, when the reciter stumbled
     * (what follows the word is to say again anyway), or went back.
     */
    fun edge(id: Int): String? {
        if (!waits || id != current || stumbledNow) return null
        val b = before ?: return null
        val to = tracker.position
        if (to < b.position || tracker.status.indices.any { it < b.position && tracker.status[it] != b.statusAt(it) }) return null
        val from = began() ?: return null
        val said = if (to > from) listOf(tracker.text(from, to)) else emptyList()
        return (finalOpening + said).joinToString(" ")
    }

    /** The mistakes whole utterance [id] (just followed) left in what it covered, if any. */
    fun toCheck(id: Int): Check? {
        if (id != current) return null
        val from = began() ?: return null
        val to = tracker.position
        if (to <= from) return null
        val bad = (from until to).filter { tracker.status[it] == WordStatus.WRONG || tracker.status[it] == WordStatus.MISSED }
        if (bad.isEmpty()) return null
        val text = (finalOpening + tracker.text(from, to)).joinToString(" ")
        return Check(id, from, to, text, bad, finalOpening.size, if (tracker.strict) bad.filter { !tracker.nearly[it] }.toSet() else emptySet())
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
        // A word the free reading did not hear takes more convincing ([CHECK_TOLERANCE_UNHEARD]):
        // «قل كان» said for «قُلْ مَن كَانَ» went on past «مَن» (the owner, 13 Nov).
        val right = check.words.filter { costs[check.opening + it - check.from] <= if (it in check.unheard) minOf(tolerance, CHECK_TOLERANCE_UNHEARD) else tolerance }
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
    private val basmalaInText = targets.any { it.ref.sura == 1 && it.ref.aya == 1 }

    fun withoutOpening(text: String): String {
        var words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        fun startsWith(phrase: String): Int {
            val p = Arabic.words(phrase).map { Arabic.skeleton(it) }
            val w = words.take(p.size).map { Arabic.skeleton(it) }
            return if (w == p) p.size else 0
        }
        /** All of what is left is [phrase]'s beginning, its last word maybe not said whole yet. */
        fun begins(phrase: String): Boolean {
            val p = Arabic.words(phrase).map { Arabic.skeleton(it) }
            val w = words.map { Arabic.skeleton(it) }
            return w.isNotEmpty() && w.size <= p.size && (0 until w.size - 1).all { w[it] == p[it] } && p[w.size - 1].startsWith(w.last())
        }
        // Still saying the isti'ādha or the basmala (a partial reading): nothing of the text yet.
        // (Read as it grew, بسم الله counted as the disconnected letters that open al-Baqara,
        // which take whatever is heard: the bench, 10 Nov.)
        if (begins(ISTIADHA)) return ""
        words = words.drop(startsWith(ISTIADHA))
        if (!tracker.done) {
            val (a, w) = tracker.locate(tracker.position)
            val ref = targets[a].ref
            if (w == 0 && !(ref.sura == 1 && ref.aya == 1)) {
                // (Not where the basmala is of the text: al-Fātiḥa's first āya, said again.)
                if (!basmalaInText && begins(BASMALA)) return ""
                words = words.drop(startsWith(BASMALA))
            }
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
        /**
         * The same for a word the free reading did not hear ([Check.unheard]): the CTC recogniser's
         * worst character at 5 nats, for 7. Measured (tools/ctc/skipstudy.py, the bench's āyāt with
         * one word more in the text than was said, 3,015 of them): a word not said costs 23 nats at
         * the median, whatever its length, but under 10 for 11 % (2.9.0's free tolerance), under 7
         * for 6 %, under 5 for 2 %, under 4 for 0.5 %. And the words said that the free reading
         * did not write (the bench's recitations said as written, noisy ones mostly: 14 checked)
         * cost 0–3.8 for six, 4.1 and 4.5 for two (each held a reciter up for five words at 4
         * nats), 6.8 and more for the rest (cut by a pause, or not heard at all).
         */
        const val CHECK_TOLERANCE_UNHEARD = 5f * 3f / 7f
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
