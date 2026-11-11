package app.hfd.core.tajweed

/**
 * The rules of tajwīd an āya's letters fall under, in the reading of Ḥafṣ from ʿĀṣim, as the
 * coloured muṣḥafs mark them. [counts] is how long the sound is held, in ḥarakāt (the time of a
 * short vowel), where the rule is one of length.
 */
enum class Rule(val family: Family, val counts: IntRange? = null) {
    /** نّ or مّ: the nasal sound held. */
    GHUNNAH(Family.GHUNNAH, 2..2),
    /** A nūn sākina or tanwīn hidden before one of the fifteen letters, with ghunna. */
    IKHFA(Family.GHUNNAH, 2..2),
    /** A mīm sākina hidden before bāʾ, with ghunna. */
    IKHFA_SHAFAWI(Family.GHUNNAH, 2..2),
    /** A nūn sākina or tanwīn turned into a mīm before bāʾ, with ghunna. */
    IQLAB(Family.GHUNNAH, 2..2),
    /** A nūn sākina or tanwīn merged into ي ن م و of the next word, with ghunna. */
    IDGHAM_GHUNNAH(Family.GHUNNAH, 2..2),
    /** A nūn sākina or tanwīn merged into ل or ر, without ghunna. */
    IDGHAM_NO_GHUNNAH(Family.SILENT),
    /** A mīm sākina merged into a mīm, with ghunna. */
    IDGHAM_SHAFAWI(Family.GHUNNAH, 2..2),
    /** A letter merged into the same letter (ٱضْرِب بِّعَصَاكَ). */
    IDGHAM_MUTAMATHILAYN(Family.SILENT),
    /** A letter merged into one of the same articulation (قَد تَّبَيَّنَ). */
    IDGHAM_MUTAJANISAYN(Family.SILENT),
    /** A letter merged into one of a near articulation (قُل رَّبِّ). */
    IDGHAM_MUTAQARIBAYN(Family.SILENT),
    /** ق ط ب ج د without a vowel: the letter rebounds. */
    QALQALAH(Family.QALQALAH),
    /** A long vowel of two ḥarakāt. */
    MADD_NATURAL(Family.MADD_2, 2..2),
    /** A long vowel at a word's end before a hamza opening the next: four or five. */
    MADD_MUNFASIL(Family.MADD_45, 4..5),
    /** A long vowel before a hamza in the same word: four or five. */
    MADD_MUTTASIL(Family.MADD_45, 4..5),
    /** A long vowel before a letter without a vowel in the same word, and the letters' names: six. */
    MADD_LAZIM(Family.MADD_6, 6..6),
    /** A long vowel before the letter the āya stops on: two, four or six. */
    MADD_ARID(Family.MADD_246, 2..6),
    /** و or ي after a fatḥa before the letter the āya stops on: two, four or six. */
    MADD_LIN(Family.MADD_246, 2..6),
    /** The alif that only sounds when a reading starts on it. */
    HAMZAT_WASL(Family.SILENT),
    /** The article's lām, merged into a sun letter. */
    LAM_SHAMSIYYAH(Family.SILENT),
    /** A letter written and not read. */
    SILENT(Family.SILENT),
    /** A rāʾ, or the lām of the Name, said heavy. */
    TAFKHIM(Family.TAFKHIM),
}

/**
 * The colours of a muṣḥaf of tajwīd: what a reader switches on or off. A letter merged into the
 * next ([Part.DROPPED]) is [SILENT]'s, whatever its rule.
 */
enum class Family { GHUNNAH, QALQALAH, MADD_2, MADD_246, MADD_45, MADD_6, SILENT, TAFKHIM }

/** Where a rule is heard ([SOUND]), or the letter it leaves unsaid ([DROPPED]: merged into the next). */
enum class Part { SOUND, DROPPED }

/**
 * Characters [from] until [to] of an āya's text fall under [rule]. [word] counts the āya's words
 * from 0 (the signs of pause are not words); [letter] is the first letter of the span, among the
 * āya's letters.
 */
data class Span(val from: Int, val to: Int, val rule: Rule, val part: Part, val word: Int, val letter: Int) {
    /** The colour it is shown in. */
    val family: Family get() = if (part == Part.DROPPED) Family.SILENT else rule.family
}

/**
 * The tajwīd of an āya read from its Uthmani text as Tanzil writes it. The script itself carries
 * most of the rulings of the muṣḥaf's scholars: a nūn with its sukūn is said clearly, one without
 * is hidden or merged; the madda sign marks the long vowels held past two; a small mīm, an iqlāb;
 * the small round zero, a letter never read. The letters' own rules give the rest and name each
 * case. The text is read as one breath to the āya's end, where the reading stops.
 */
object Tajweed {
    fun annotate(text: String): List<Span> = Reading(text).spans

    /** For the tests: what the reading could not place. */
    internal fun doubts(text: String): List<String> = Reading(text).doubts
}

internal const val FATHA = '\u064E'
internal const val DAMMA = '\u064F'
internal const val KASRA = '\u0650'
internal const val FATHATAN = '\u064B'
internal const val DAMMATAN = '\u064C'
internal const val KASRATAN = '\u064D'
internal const val SHADDA = '\u0651'
internal const val SUKUN = '\u0652'
internal const val MADDAH = '\u0653'
internal const val HAMZA_ABOVE = '\u0654'
internal const val DAGGER = '\u0670'
internal const val WASL = '\u0671'
internal const val TATWEEL = '\u0640'
internal const val ALIF = '\u0627'
internal const val ALIF_MAQSURA = '\u0649'
internal const val YA = '\u064A'
internal const val WAW = '\u0648'
internal const val SMALL_WAW = '\u06E5'
internal const val SMALL_YA = '\u06E6'
internal const val SMALL_HIGH_YA = '\u06E7'
internal const val ZERO = '\u06DF'
internal const val RECT_ZERO = '\u06E0'
internal const val HIGH_MEEM = '\u06E2'
internal const val LOW_MEEM = '\u06ED'
internal const val SAKT = '\u06DC'
internal const val TASHIL = '\u06EC'
internal const val IMALA = '\u06EA'

internal enum class Kind { CONSONANT, MADD, LIN, SILENT, SEAT, WASL, NAME }

/** A letter with the signs written on it. */
internal class Letter(val ch: Char, val from: Int, val word: Int, val index: Int) {
    var to = from + 1
    var vowel = 0.toChar()
    var vowelAt = -1
    var shadda = false
    var sukun = false
    var maddah = false
    var hamzaAbove = false
    var dagger = false
    var daggerAt = -1
    var zero = false
    var rect = false
    var meem = false
    var sakt = false
    var tashil = false
    var imala = false
    var kind = Kind.CONSONANT

    /** A long ā written as a dagger alif on this consonant: it follows the letter. */
    var virtual = false

    val tanwin get() = vowel == FATHATAN || vowel == DAMMATAN || vowel == KASRATAN
    val short get() = vowel == FATHA || vowel == DAMMA || vowel == KASRA
    val bare get() = vowel == 0.toChar() && !sukun && !shadda
    val hamza get() = ch in HAMZAT || hamzaAbove
    val small get() = ch == SMALL_WAW || ch == SMALL_YA || ch == SMALL_HIGH_YA

    companion object {
        const val HAMZAT = "\u0621\u0623\u0624\u0625\u0626"
    }
}

internal class Reading(val text: String) {
    val letters = ArrayList<Letter>()
    val spans = ArrayList<Span>()
    val doubts = ArrayList<String>()

    /** The words' texts, to know the letters that open some sūras. */
    private val words = ArrayList<String>()
    private val taken = HashSet<Int>()

    private fun isLetter(c: Char) = c in '\u0621'..'\u064A' || c == WASL || c == SMALL_WAW || c == SMALL_YA || c == SMALL_HIGH_YA

    private fun parse() {
        var word = -1
        var i = 0
        while (i < text.length) {
            if (text[i] == ' ') { i++; continue }
            var end = i
            while (end < text.length && text[end] != ' ') end++
            if ((i until end).none { isLetter(text[it]) && text[it] != TATWEEL }) { i = end; continue } // a sign of pause, of sajda, of a quarter
            word++
            words += text.substring(i, end)
            var current: Letter? = null
            var closed = false
            for (k in i until end) {
                val c = text[k]
                if (isLetter(c)) {
                    current = Letter(c, k, word, letters.size).also { letters += it }
                    closed = false
                    continue
                }
                val l = current ?: continue
                l.to = k + 1
                if (closed) continue
                when (c) {
                    FATHA, DAMMA, KASRA, FATHATAN, DAMMATAN, KASRATAN -> { l.vowel = c; l.vowelAt = k }
                    SHADDA -> l.shadda = true
                    SUKUN -> l.sukun = true
                    MADDAH -> l.maddah = true
                    HAMZA_ABOVE -> if (l.ch == TATWEEL || l.ch == ALIF_MAQSURA || l.ch == YA || l.ch == WAW) l.hamzaAbove = true else {
                        // فَٱدَّٰرَْٰٔتُمْ: a hamza without a vowel written after the letter, on a dagger alif that is only its seat.
                        l.dagger = false
                        closed = true
                    }
                    DAGGER -> { l.dagger = true; l.daggerAt = k }
                    ZERO -> l.zero = true
                    RECT_ZERO -> l.rect = true
                    HIGH_MEEM, LOW_MEEM -> l.meem = true
                    SAKT -> l.sakt = true
                    TASHIL -> l.tashil = true
                    // مَجْر۪ىٰهَا: the fatḥa leant toward a kasra.
                    IMALA -> { l.vowel = FATHA; l.vowelAt = k; l.imala = true }
                }
            }
            i = end
        }
    }

    /** The letter of [l]'s word read before it (وَجِا۟ىٓءَ: the alif between is never read). */
    private fun before(l: Letter): Letter? {
        var k = l.index - 1
        while (k >= 0 && letters[k].word == l.word) {
            if (!letters[k].zero && !(letters[k].ch == TATWEEL && !letters[k].hamzaAbove)) return letters[k]
            k--
        }
        return null
    }

    /** What each letter is in the reading: a consonant, a long vowel, a letter left unread. */
    private fun kinds() {
        for (l in letters) {
            val p = before(l)
            l.kind = when {
                words[l.word] in FAWATIH -> Kind.NAME
                l.ch == WASL -> Kind.WASL
                l.zero -> Kind.SILENT
                // A line that only carries a small letter or sign.
                l.ch == TATWEEL && !l.hamzaAbove -> Kind.SILENT
                l.small -> if (l.bare) Kind.MADD else Kind.CONSONANT
                l.ch == ALIF -> when {
                    l.tashil -> Kind.CONSONANT
                    // أَنَا۠: read only when the reading stops on it.
                    l.rect -> if (l === letters.last()) Kind.MADD else Kind.SILENT
                    p == null -> Kind.SILENT.also { doubts += "alif opening ${words[l.word]}" }
                    p.vowel == FATHATAN -> Kind.SEAT
                    p.vowel == FATHA && !p.dagger -> Kind.MADD
                    else -> Kind.SILENT.also { doubts += "alif after ${p.ch} in ${words[l.word]}" }
                }
                l.ch == ALIF_MAQSURA || l.ch == YA -> when {
                    l.hamzaAbove -> Kind.CONSONANT
                    l.dagger && l.vowel == 0.toChar() && !l.shadda -> Kind.MADD
                    !l.bare -> if (l.sukun && p?.vowel == FATHA) Kind.LIN else Kind.CONSONANT
                    p == null -> Kind.CONSONANT.also { doubts += "bare yāʾ opening ${words[l.word]}" }
                    p.vowel == FATHATAN -> Kind.SEAT
                    p.vowel == KASRA -> Kind.MADD
                    // An alif written as a yāʾ, dropped before the next word's joining alif (مُوسَى ٱلْكِتَٰبَ);
                    // at an āya's end (ٱلْأَشْقَى, before the next āya's ٱلَّذِى) the reading stops on it.
                    p.vowel == FATHA -> if (l === letters.last()) Kind.MADD else Kind.SILENT
                    else -> Kind.CONSONANT.also { doubts += "bare yāʾ after ${p.ch} in ${words[l.word]}" }
                }
                l.ch == WAW -> when {
                    l.hamzaAbove -> Kind.CONSONANT
                    l.dagger && l.vowel == 0.toChar() && !l.shadda -> Kind.MADD
                    !l.bare -> if (l.sukun && p?.vowel == FATHA) Kind.LIN else Kind.CONSONANT
                    p?.vowel == DAMMA -> Kind.MADD
                    // عَصَوا۟ وَّكَانُوا۟: a wāw without a vowel after a fatḥa, merged into the next word's.
                    p?.vowel == FATHA -> Kind.CONSONANT
                    else -> Kind.CONSONANT.also { doubts += "bare wāw in ${words[l.word]}" }
                }
                else -> Kind.CONSONANT
            }
            // A dagger alif on a consonant is the long vowel after it.
            if (l.kind == Kind.CONSONANT && l.dagger) l.virtual = true
        }
    }

    /** The letter sounded after [l]: the letters never read are passed over. */
    private fun next(l: Letter, from: Int = l.index + 1): Letter? {
        var k = from
        while (k < letters.size) {
            val n = letters[k]
            if (n.kind != Kind.SILENT && n.kind != Kind.SEAT) return n
            k++
        }
        return null
    }

    private fun mark(l: Letter, rule: Rule, part: Part = Part.SOUND, from: Int = l.from, to: Int = l.to) {
        spans += Span(from, to, rule, part, l.word, l.index)
    }

    /** The last letter sounded when the reading stops at the āya's end, and whether it is a consonant the voice rests on. */
    private val last: Letter? by lazy { letters.lastOrNull { it.kind != Kind.SILENT || it.rect } }

    /** The consonant the āya stops on: its vowel is dropped there. Null when the stop is on a long vowel. */
    private val stop: Letter? by lazy {
        val l = last ?: return@lazy null
        when {
            l.kind == Kind.SEAT -> null // …ًا: the stop is on the alif
            l.kind != Kind.CONSONANT && l.kind != Kind.LIN -> null
            l.vowel == FATHATAN && l.ch != '\u0629' -> null // مَآءً: the tanwīn becomes an alif
            l.virtual -> null
            else -> l
        }
    }

    init {
        parse()
        kinds()
        rules()
        spans.sortWith(compareBy({ it.from }, { it.to }))
    }

    private fun rules() {
        for (l in letters) {
            when (l.kind) {
                Kind.NAME -> name(l)
                Kind.WASL -> if (l.index > 0) mark(l, Rule.HAMZAT_WASL, Part.DROPPED)
                Kind.SILENT -> if (l.zero || l.rect) {
                    // (The small upright zero's letter is read when the reading stops on it.)
                    if (!(l.rect && l === last)) mark(l, Rule.SILENT, Part.DROPPED)
                }
                // ٱلصَّلَوٰةَ: the wāw is not read, the dagger alif on it is the long vowel.
                Kind.MADD -> if (l.ch == WAW && l.dagger) {
                    mark(l, Rule.SILENT, Part.DROPPED, l.from, l.from + 1)
                    madd(l, l.daggerAt, l.to, next(l))
                } else madd(l, l.from, l.to, next(l))
                Kind.LIN -> if (next(l) === stop && stop != null) mark(l, Rule.MADD_LIN)
                Kind.SEAT -> Unit
                Kind.CONSONANT -> Unit
            }
            if (l.kind == Kind.CONSONANT || l.kind == Kind.LIN) consonant(l)
        }
    }

    /** A long vowel written from [from] until [to], before the letter [n]. */
    private fun madd(l: Letter, from: Int, to: Int, n: Letter?) {
        val rule = when {
            // The madda sign on an āya's last vowel is for joining it to the next, which opens with a hamza.
            n == null -> if (l.maddah) Rule.MADD_MUNFASIL else Rule.MADD_NATURAL
            // Before a joining alif the long vowel is dropped: two letters without a vowel meet.
            n.kind == Kind.WASL -> null
            n.hamza && n.word == l.word -> if (vocative(l)) Rule.MADD_MUNFASIL else Rule.MADD_MUTTASIL
            n.hamza -> Rule.MADD_MUNFASIL
            n.word == l.word && n.kind == Kind.CONSONANT && (n.sukun || n.shadda || n.bare) -> Rule.MADD_LAZIM
            n === stop -> Rule.MADD_ARID
            else -> Rule.MADD_NATURAL
        }
        val held = rule == Rule.MADD_MUNFASIL || rule == Rule.MADD_MUTTASIL || rule == Rule.MADD_LAZIM
        if (rule != null && held != l.maddah) doubts += "madda sign ${if (l.maddah) "on" else "missing from"} a ${rule.name} in ${words[l.word]}"
        if (rule != null) mark(l, rule, from = from, to = to)
    }

    /** يَٰٓأَيُّهَا, هَٰٓؤُلَآءِ, هَٰٓأَنتُمْ: two words written as one, the call's long vowel before the hamza of the next. */
    private fun vocative(l: Letter): Boolean {
        val first = letters.first { it.word == l.word }
        return l === first && l.virtual && (l.ch == YA || l.ch == '\u0647')
    }

    private fun consonant(l: Letter) {
        val n = next(l)
        // The long ā of a dagger alif on this letter.
        if (l.virtual) {
            var to = l.daggerAt + 1
            while (to < l.to && (text[to] == MADDAH)) to++
            madd(l, l.daggerAt, to, n)
        }
        if (l.kind == Kind.LIN) return
        val atStop = l === stop
        when {
            // Tanwīn: a nūn sākina in sound, unless the reading stops on it.
            l.tanwin && !atStop -> if (n != null) nun(l, n, tanwin = true)
            l.ch == NUN && l.bare -> if (n != null) nun(l, n, tanwin = false)
            l.ch == MIM && l.bare -> when (n?.ch) {
                BA -> mark(l, Rule.IKHFA_SHAFAWI)
                MIM -> { mark(l, Rule.IDGHAM_SHAFAWI, Part.DROPPED); receive(n, Rule.IDGHAM_SHAFAWI) }
                // (At an āya's end: written for joining it to the next.)
                null -> Unit
                else -> doubts += "bare mīm in ${words[l.word]}"
            }
            l.bare && !l.hamza && l.ch != TATWEEL && !l.tashil -> merged(l, n)
        }
        // (A shadda on an āya's first letter is for joining it to the āya before.)
        if ((l.ch == NUN || l.ch == MIM) && l.shadda && l.index !in taken && l.index > 0) mark(l, Rule.GHUNNAH)
        if (l.ch in QALQALAH && (l.sukun || atStop)) mark(l, Rule.QALQALAH)
        if (l.ch == RA && heavyRa(l)) mark(l, Rule.TAFKHIM)
        if (l.shadda && theName(l) && heavyName(l)) mark(l, Rule.TAFKHIM)
    }

    /**
     * Whether a rāʾ is said heavy. With a fatḥa or a ḍamma it is, with a kasra it is not; without
     * a vowel (written so, or because the āya stops on it) it follows what comes before it.
     */
    private fun heavyRa(l: Letter): Boolean {
        val word = words[l.word]
        val p = before(l)
        return when {
            l.imala -> false
            l === stop -> when {
                // وَٱلَّيْلِ إِذَا يَسْرِ: light is preferred, for the yāʾ the word lost.
                word == YASRI -> false
                p == null -> true
                p.kind == Kind.MADD -> !(p.ch == YA || p.ch == ALIF_MAQSURA || p.ch == SMALL_YA || p.ch == SMALL_HIGH_YA) || p.dagger
                p.kind == Kind.LIN -> p.ch == WAW
                p.virtual -> true
                p.short -> p.vowel != KASRA
                // A letter without a vowel between: the vowel before it decides (ٱلْقَدْرِ heavy, ٱلذِّكْرِ light).
                else -> before(p)?.let { q -> if (q.kind == Kind.MADD) !(q.ch == YA || q.ch == ALIF_MAQSURA) else q.vowel != KASRA } ?: true
            }
            l.vowel == FATHA || l.vowel == DAMMA || l.vowel == FATHATAN || l.vowel == DAMMATAN -> true
            l.vowel == KASRA || l.vowel == KASRATAN -> false
            !l.sukun -> false
            p == null -> true
            // ٱرْجِعُوٓا۟: the kasra of a joining alif, or of the word before, is not the word's own.
            p.kind == Kind.WASL -> true
            p.vowel == KASRA -> {
                // قِرْطَاسٍ, مِرْصَادًا, فِرْقَةٍ: a heavy letter after it in the word keeps it heavy, unless that letter has a kasra (فِرْقٍ).
                val n = letters.getOrNull(l.index + 1)?.takeIf { it.word == l.word }
                n != null && n.ch in ISTILA && n.vowel != KASRA && n.vowel != KASRATAN
            }
            else -> true
        }
    }

    /** The lām of ٱللَّه is heavy after a fatḥa or a ḍamma (and when a reading starts on the Name), light after a kasra. */
    private fun heavyName(lam: Letter): Boolean {
        var k = lam.index - 1
        while (k >= 0) {
            val l = letters[k]
            when {
                // A tanwīn takes a kasra before the joining alif (قَوْمًا ٱللَّهُ).
                l.tanwin -> return false
                l.short -> return l.vowel != KASRA
            }
            k--
        }
        return true
    }

    /** The letter a merged one goes into carries the rule's sound (its ghunna). */
    private fun receive(n: Letter, rule: Rule) {
        taken += n.index
        mark(n, rule)
    }

    private fun nun(l: Letter, n: Letter, tanwin: Boolean) {
        // A tanwīn is coloured on its sign (with the small mīm of an iqlāb); a nūn, whole.
        val from = if (tanwin) l.vowelAt else l.from
        val to = if (tanwin) { var t = l.vowelAt + 1; while (t < l.to && (text[t] == HIGH_MEEM || text[t] == LOW_MEEM)) t++; t } else l.to
        fun put(rule: Rule, part: Part = Part.SOUND) = mark(l, rule, part, from, to)
        when {
            l.meem -> put(Rule.IQLAB)
            n.kind == Kind.WASL -> Unit // the tanwīn takes a kasra before a joining alif
            n.kind == Kind.NAME -> Unit
            n.word == l.word && !tanwin && n.ch in "\u064A\u0648" -> doubts += "nūn before ${n.ch} inside ${words[l.word]}"
            n.ch in "\u064A\u0649\u0646\u0645\u0648" && n.kind != Kind.MADD -> { put(Rule.IDGHAM_GHUNNAH, Part.DROPPED); receive(n, Rule.IDGHAM_GHUNNAH) }
            n.ch in "\u0644\u0631" -> put(Rule.IDGHAM_NO_GHUNNAH, Part.DROPPED)
            n.ch == BA -> put(Rule.IQLAB).also { doubts += "iqlāb without its mīm in ${words[l.word]}" }
            n.ch in IKHFA -> put(Rule.IKHFA)
            n.hamza || n.ch in THROAT -> if (!tanwin) doubts += "nūn without sukūn before ${n.ch} in ${words[l.word]}"
            else -> doubts += "nūn before ${n.ch} in ${words[l.word]}"
        }
    }

    /** A consonant written without any sign: it is merged into the next. */
    private fun merged(l: Letter, n: Letter?) {
        // (At an āya's end: written for joining it to the next, فَٱرْغَب before the basmala.)
        if (n == null) return
        when {
            // The article's lām before a sun letter (ٱلشَّمْس, لِلنَّاسِ, ءَآلذَّكَرَيْنِ); in ٱللَّه the two lāms are one heavy or light letter.
            l.ch == LAM && n.word == l.word && n.shadda -> if (!theName(n)) mark(l, Rule.LAM_SHAMSIYYAH, Part.DROPPED)
            n.ch == l.ch && n.shadda -> mark(l, Rule.IDGHAM_MUTAMATHILAYN, Part.DROPPED)
            GROUPS.any { l.ch in it && n.ch in it } && (n.shadda || (l.ch == '\u0637' && n.ch == '\u062A')) -> mark(l, Rule.IDGHAM_MUTAJANISAYN, Part.DROPPED)
            NEAR.any { l.ch in it && n.ch in it } && n.shadda -> mark(l, Rule.IDGHAM_MUTAQARIBAYN, Part.DROPPED)
            else -> doubts += "bare ${l.ch} before ${n.ch} in ${words[l.word]}"
        }
    }

    /** [lam] is the doubled lām of ٱللَّه (or ٱللَّهُمَّ): the word ends on the hāʾ after it. */
    private fun theName(lam: Letter): Boolean {
        if (lam.ch != LAM) return false
        val rest = letters.subList(lam.index + 1, letters.size).takeWhile { it.word == lam.word }
        val h = rest.firstOrNull() ?: return false
        return h.ch == '\u0647' && !h.sukun && (rest.size == 1 || (rest.size == 2 && rest[1].ch == MIM && rest[1].shadda))
    }

    /** The letters that open some sūras are read by their names: six ḥarakāt under the madda sign, two for ح ي ط ه ر. */
    private fun name(l: Letter) {
        when {
            l.maddah -> mark(l, Rule.MADD_LAZIM)
            l.ch in "\u062D\u064A\u0637\u0647\u0631" -> mark(l, Rule.MADD_NATURAL)
        }
    }

    companion object {
        const val NUN = '\u0646'
        const val MIM = '\u0645'
        const val BA = '\u0628'
        const val LAM = '\u0644'
        const val RA = '\u0631'
        const val ISTILA = "\u062E\u0635\u0636\u063A\u0637\u0642\u0638"
        const val YASRI = "\u064A\u064E\u0633\u0652\u0631\u0650"
        const val QALQALAH = "\u0642\u0637\u0628\u062C\u062F"
        const val THROAT = "\u0621\u0647\u0639\u062D\u063A\u062E"
        const val IKHFA = "\u0635\u0630\u062B\u0643\u062C\u0634\u0642\u0633\u062F\u0637\u0632\u0641\u062A\u0636\u0638"

        /** Letters of one articulation. */
        val GROUPS = listOf("\u062A\u062F\u0637", "\u062B\u0630\u0638", "\u0628\u0645")

        /** Letters of near articulations. */
        val NEAR = listOf("\u0644\u0631", "\u0642\u0643")

        /** The letters that open twenty-nine sūras, as Tanzil writes them. */
        val FAWATIH = setOf(
            "\u0627\u0644\u0653\u0645\u0653", "\u0627\u0644\u0653\u0645\u0653\u0635\u0653", "\u0627\u0644\u0653\u0631", "\u0627\u0644\u0653\u0645\u0653\u0631",
            "\u0643\u0653\u0647\u064A\u0639\u0653\u0635\u0653", "\u0637\u0647", "\u0637\u0633\u0653\u0645\u0653", "\u0637\u0633\u0653", "\u064A\u0633\u0653",
            "\u0635\u0653", "\u062D\u0645\u0653", "\u0639\u0653\u0633\u0653\u0642\u0653", "\u0642\u0653", "\u0646\u0653",
        )
    }
}
