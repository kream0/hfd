package app.hfd.core.quran

import app.hfd.core.recite.Arabic
import app.hfd.core.recite.Tracker

/**
 * Where a recitation is in the Qur'an, found as it is heard: the recogniser's readings of what is
 * being said ([heard], again and again as an utterance grows, then whole) are looked for in the
 * whole text ([QuranSearch.recited]) until one place stands out; the recitation is then followed
 * from that place's first word.
 *
 * What is not looked for: the isti'ādha and the basmala said before a passage (the basmala is
 * al-Fātiḥa's first āya only when the sūra's second follows it), and what isn't a word of the
 * text at the phrase's beginning (a breath, half a word, and above all the disconnected letters
 * that open a sūra, which the recogniser writes as a broken basmala: الٓمٓ comes out بِسٱللَّمٓ).
 * A sūra whose opening letters were said before the place found is followed from those letters.
 *
 * A phrase the text says in several places waits for what is said next, unless one of them is
 * where the reciter is likely to be ([near]: the sūra open, or being recited). Once the place is
 * known, it is taken as far back as what was said before it goes on fitting: a recitation begun
 * on words several sūras share is followed from where it began.
 */
class Locator(
    private val text: QuranSearch,
    private val near: AyahRef? = null,
    /** Letters a phrase needs to be looked for (more, to leave a text being followed for another place). */
    private val minLetters: Int = MIN_LETTERS,
) {
    /** The place found (the āya and its word the recitation is followed from), and what was heard from there on, for the follower. */
    class Found(val ref: AyahRef, val word: Int, val score: Float, val phrase: String)

    /** The places that say what was heard so far, while none stands out. */
    var candidates: List<QuranSearch.Recited> = emptyList()
        private set

    /** Whole utterances heard before the one being said, that settled nothing (their words). */
    private val said = ArrayList<List<String>>()

    /** How the utterance that found the place was read, to read its later readings the same way ([phrase]). */
    private var prefix: List<String> = emptyList()
    private var settled: Attempt? = null

    /** The place the last reading pointed to: one still growing must point to it twice. */
    private var steady: Pair<AyahRef, Int>? = null

    /**
     * A reading of the utterance being said ([final]: whole). Returns the place once it stands
     * out; null while it doesn't (more is needed: [candidates] lists the places so far).
     */
    fun heard(reading: String, final: Boolean): Found? {
        val now = words(reading)
        val found = decide(now, final)
        if (found == null && final && now.isNotEmpty()) {
            said += now
            while (said.size > KEPT_UTTERANCES || (said.sumOf { it.size } > KEPT_WORDS && said.size > 1)) said.removeAt(0)
        }
        return found
    }

    /** A later reading of the utterance that found the place, as the follower is to be given it. */
    fun phrase(reading: String): String {
        val was = settled ?: return reading
        val again = attempt(prefix + words(reading), final = true, keepBasmala = was.basmala) ?: return reading
        return (if (was.back) again.before + again.phrase else again.phrase).joinToString(" ")
    }

    /** What a stretch of readings comes to, once the opening formulas and what isn't the text's are set aside. */
    private class Attempt(
        /** What isn't a word of the text, before the phrase (the sound of a sūra's opening letters, a breath…). */
        val before: List<String>,
        val phrase: List<String>,
        val basmala: Boolean,
        val hits: List<QuranSearch.Recited> = emptyList(),
        val pick: QuranSearch.Recited? = null,
        /** The places that say the phrase as well as the best one does (one of them is [pick], when one stands out). */
        val close: List<QuranSearch.Recited> = emptyList(),
        /** The place is taken back to the sūra's opening letters, said before the phrase. */
        val back: Boolean = false,
    )

    private fun decide(now: List<String>, final: Boolean): Found? {
        if (now.isEmpty()) return null
        // The utterance alone, then with what was said before it: a short phrase needs its
        // context; and once a place stands out, what was said before is tried in front of it for
        // as long as it fits (the place is then where the recitation began).
        var best: Attempt? = null
        var used = 0
        var listed: List<QuranSearch.Recited> = emptyList()
        for (back in 0..said.size) {
            val tried = try2(said.takeLast(back).flatten() + now, final)
            val had = best?.pick
            // (A longer stretch two places say almost as well: the one the place already found is in,
            // if what found it is enough to settle a place: ten letters are not, وَمَن يُشَاقِّ ٱللَّهَ is in
            // al-Ḥashr 4 and, a letter apart, in al-Anfāl 13, whose beginnings are the same.)
            val sure = had != null && letters(best!!.phrase) >= MIN_LETTERS_SPEAKING
            val pick = tried?.pick ?: had?.takeIf { sure }?.let { h -> tried?.close?.firstOrNull { fits(it, h) } }
            val a = if (tried != null && pick != null && tried.pick == null) Attempt(tried.before, tried.phrase, tried.basmala, tried.hits, pick, tried.close) else tried
            when {
                pick != null && (had == null || fits(pick, had)) -> { best = a; used = back }
                had != null -> break
                listed.isEmpty() && a != null -> listed = a.hits
            }
        }
        var a = best
        if (a?.pick == null) {
            // Several places say it, and what was said before isn't the text's: the place that
            // follows a sūra's opening letters, if one alone does (ٱللَّهُ لَآ إِلَٰهَ إِلَّا هُوَ ٱلْحَىُّ ٱلْقَيُّومُ after
            // الٓمٓ is Āl ʿImrān's, not Āyat al-Kursī).
            val tied = try2(now, final)?.takeIf { it.pick == null && it.close.size > 1 }
            val sound = tied?.before.orEmpty().ifEmpty { said.lastOrNull().orEmpty().takeIf { u -> u.isNotEmpty() && !text.known(u[0]) }.orEmpty() }
            val after = if (tied != null && sound.isNotEmpty()) tied.close.filter { opening(it, emptyList()) != null } else emptyList()
            if (tied != null && after.size == 1) a = Attempt(tied.before, tied.phrase, tied.basmala, tied.hits, after[0], tied.close)
        }
        val pick = a?.pick
        if (a == null || pick == null) {
            steady = null
            if (listed.isNotEmpty() || final) candidates = listed
            return null
        }
        // Said just before the place found, and not the text's (in the same breath, or the
        // utterance before): a sūra's opening, if that is what lies before the place. The
        // recogniser writes a sūra's first words as a broken basmala, its opening letters and
        // more («بِسٱهِلٱلرَّحَحٰ…» for سَبَّحَ لِلَّهِ: its training put the basmala on every sūra's first
        // āya). The place is then the sūra's first word, with that sound in front of the phrase.
        val earlier = said.getOrNull(said.size - 1 - used).orEmpty()
        val sound = a.before.ifEmpty { earlier }
        val start = if (sound.isNotEmpty()) opening(pick, a.before) else null
        val ref = start ?: pick.ref
        val word = if (start != null) 0 else pick.word
        val twice = steady == ref to word
        steady = ref to word
        candidates = emptyList()
        if (!final && (!twice || letters(a.phrase) < MIN_LETTERS_SPEAKING)) return null
        val before = said.takeLast(used).flatten()
        prefix = if (start != null && a.before.isEmpty()) earlier + before else before
        settled = Attempt(sound, a.phrase, a.basmala, back = start != null)
        return Found(ref, word, pick.score, (if (start != null) sound + a.phrase else a.phrase).joinToString(" "))
    }

    /** [all] read with the basmala as al-Fātiḥa's first āya if that is what it is, else without. */
    private fun try2(all: List<String>, final: Boolean): Attempt? {
        attempt(all, final, keepBasmala = true)?.let { if (it.pick != null) return it }
        return attempt(all, final, keepBasmala = false)
    }

    private fun attempt(all: List<String>, final: Boolean, keepBasmala: Boolean): Attempt? {
        val skeletons = all.map { Arabic.skeleton(it) }
        // The isti'ādha and the basmala as they are heard, not letter for letter: the recogniser
        // has only learnt the Qur'an's text, where the isti'ādha is not. It writes it with a
        // letter off or with the «بس» it begins a sūra with («أَعَوذُ بِٱللَِّ مِنَ ٱلشَّيْطَـٰنِ ٱلرَّجِيمِ»,
        // «بِسْأَعُوذُ بِٱلللَّهِ…»), which then read as the end of an-Naḥl 98 (the owner, 13 Nov:
        // placed there by the isti'ādha, al-Baqara recited after it was not followed).
        /** The words from [from] to the end are [formula]'s beginning, or all of it. */
        fun begins(formula: String, from: Int): Boolean {
            val s = skeletons.drop(from).joinToString("")
            return s.length >= 2 && s.length <= formula.length + FORMULA_SLACK && ahead(s, formula) <= maxOf(1, s.length / 4)
        }
        /** How many words from [from] are [formula] said whole, with more after them; 0 if they are not. */
        fun starts(formula: String, words: Int, from: Int): Int {
            var best = 0
            var least = formula.length / 4 + 1
            for (k in maxOf(1, words - 2)..minOf(words + 1, skeletons.size - from)) {
                val d = distance(skeletons.subList(from, from + k).joinToString(""), formula)
                if (d < least) { least = d; best = k }
            }
            return best
        }
        // Still saying the isti'ādha or the basmala: nothing of the text yet.
        if (begins(ISTIADHA, 0)) return null
        var from = starts(ISTIADHA, ISTIADHA_WORDS, 0)
        if (begins(BASMALA_LETTERS, from)) return null
        val formula = starts(BASMALA_LETTERS, BASMALA.size, from)
        val basmala = formula > 0
        if (keepBasmala) {
            // Al-Fātiḥa, if its second āya is what follows the basmala (among the places that say it).
            if (!basmala) return null
            val rest = all.drop(from + formula)
            if (letters(rest) < MIN_LETTERS_AFTER_BASMALA) return null
            val hits = text.recited(rest.joinToString(" "))
            val top = hits.firstOrNull() ?: return null
            val second = hits.firstOrNull { top.score - it.score < MARGIN && it.ref == FATIHA_SECOND && it.word == 0 } ?: return null
            if (second.score < MIN_SCORE) return null
            val pick = QuranSearch.Recited(FATIHA, 0, second.last, second.score, second.whole + BASMALA.size)
            return Attempt(emptyList(), all.drop(from), true, hits, pick, listOf(pick))
        }
        if (basmala) from += formula
        // What isn't a word of the text, at the beginning; and at the end (a word half said, or
        // heard as none: its few letters must not choose between two places).
        var first = from
        while (first < all.size && first - from < MAX_STRAY_WORDS && !text.known(all[first])) first++
        var end = all.size
        if (end > first + 2 && !text.known(all[end - 1])) end--
        while (true) {
            val phrase = all.subList(first, end)
            if (letters(phrase) < minLetters) return Attempt(all.subList(from, first), phrase, false)
            val hits = text.recited(phrase.joinToString(" "))
            val close = close(hits, phrase.size)
            val pick = pick(close)
            // The place must begin with the phrase's first word. A word that is another's there (a
            // few of its letters found among the words before: half a word, a breath read as one)
            // is set aside, and the place has to stand out without it. («ممة وهم لا يشعرون» was found
            // in Yūsuf alone, by بأمرهم هذا before the three words that eight āyāt end with.)
            if (pick != null && phrase.size > 1 && first - from < MAX_STRAY_WORDS && !alike(Arabic.skeleton(phrase[0]), text.wordAt(pick.ref, pick.word))) {
                first++
                continue
            }
            // The basmala, then al-Fātiḥa's beginning (its second āya not read well enough to be
            // found from its first word, or its first word's sound alone): the sūra's first āya.
            if (basmala && pick != null && pick.ref.sura == 1 && pick.ref.aya == 2) {
                val whole = QuranSearch.Recited(FATIHA, 0, pick.last, pick.score, pick.whole + BASMALA.size)
                return Attempt(emptyList(), all.drop(from - formula), true, hits, whole, listOf(whole))
            }
            return Attempt(all.subList(from, first), phrase, false, hits, pick, close)
        }
    }

    /** Letters changed, added or left out between [a] and [b]. */
    private fun distance(a: String, b: String): Int = edits(a, b).last()

    /** The same between [a] and the beginning of [b] nearest to it. */
    private fun ahead(a: String, b: String): Int = edits(a, b).min()

    /** For each beginning of [b] (none of it, its first letter, …, all of it): the letters to change, add or leave out to make [a] of it. */
    private fun edits(a: String, b: String): IntArray {
        var row = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val next = IntArray(b.length + 1)
            next[0] = i
            for (j in 1..b.length) next[j] = minOf(row[j] + 1, next[j - 1] + 1, row[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            row = next
        }
        return row
    }

    /** The same word, or one letter from it (as heard). */
    private fun alike(heard: String, word: String?): Boolean {
        if (word == null) return false
        if (heard == word) return true
        // (The «و» or «ف» a word begins with, not heard or heard where there is none: وَمَا / ما.)
        if (word.length == heard.length + 1 && word[0] in "وف" && word.endsWith(heard)) return true
        if (heard.length == word.length + 1 && heard[0] in "وف" && heard.endsWith(word)) return true
        if (minOf(heard.length, word.length) < 3 || kotlin.math.abs(heard.length - word.length) > 1) return false
        // One letter changed, added or left out.
        var i = 0
        while (i < heard.length && i < word.length && heard[i] == word[i]) i++
        val a = heard.substring(i)
        val b = word.substring(i)
        return a.drop(1) == b.drop(1) || a.drop(1) == b || a == b.drop(1)
    }

    /** The places among [hits] that say the phrase (of [words] words) as well as the best one does; none if that one doesn't say it well enough. */
    private fun close(hits: List<QuranSearch.Recited>, words: Int): List<QuranSearch.Recited> {
        val best = hits.firstOrNull() ?: return emptyList()
        val whole = minOf(MIN_WHOLE_WORDS, words)
        if (best.score < MIN_SCORE || best.tight < MIN_TIGHT || best.whole < whole) return emptyList()
        return hits.filter { best.score - it.score < MARGIN && it.whole >= whole }
    }

    /** The place among those ([close]) that stands out, if one does. */
    private fun pick(close: List<QuranSearch.Recited>): QuranSearch.Recited? {
        if (close.size <= 1) return close.firstOrNull()
        // Several places say it as well: the one in the sūra the reciter is in, the first after where they are.
        val n = near ?: return null
        val there = close.filter { it.ref.sura == n.sura }
        return there.firstOrNull { it.ref >= n } ?: there.firstOrNull()
    }

    /** Whether the place a longer stretch found ([longer]) is the one its end alone found ([shorter]), from further back. */
    private fun fits(longer: QuranSearch.Recited, shorter: QuranSearch.Recited): Boolean =
        longer.ref.sura == shorter.ref.sura && longer.ref <= shorter.ref && longer.last >= shorter.ref && shorter.ref.aya - longer.ref.aya <= MAX_AYAT_BACK

    /**
     * The sūra's first āya, if [pick] is in the sūra's opening and what was said before it is
     * that opening: only its letters lie before the place; or no more words than were set aside
     * in front of the phrase ([strays]), two more at most, in the sūra's first āya, or past it
     * when the strays have the broken basmala's «بس» (the opening's words as the recogniser
     * writes them; half a word heard from inside the second āya is not that).
     */
    private fun opening(pick: QuranSearch.Recited, strays: List<String>): AyahRef? {
        val first = AyahRef(pick.ref.sura, 1)
        val ayat = (1 until pick.ref.aya).map { AyahRef(pick.ref.sura, it) }
        val before = ayat.sumOf { text.wordCount(it) } + pick.word
        if (before == 0) return null
        // (Al-Fātiḥa from its second āya, something said before it: its basmala, not heard as one.)
        if (pick.ref == FATIHA_SECOND && pick.word == 0 && strays.isNotEmpty()) return first
        val letters = ayat.all { Tracker.isLetters(it) && text.wordCount(it) == 1 } &&
            (pick.word == 0 || (pick.word == 1 && Tracker.isLetters(pick.ref)))
        if (letters && Tracker.isLetters(first)) return first
        if (strays.isEmpty() || before > minOf(strays.size + 2, MAX_OPENING_WORDS)) return null
        return first.takeIf { pick.ref == first || strays.any { Arabic.skeleton(it).startsWith("بس") } }
    }

    private fun words(reading: String): List<String> = reading.split(Regex("\\s+")).filter { Arabic.skeleton(it).isNotEmpty() }

    private fun letters(words: List<String>): Int = words.sumOf { Arabic.skeleton(it).length }

    companion object {
        /** Letters (of the skeleton) a phrase needs to be looked for, and to settle a place while it is still being said. */
        const val MIN_LETTERS = 9
        const val MIN_LETTERS_SPEAKING = 14
        const val MIN_LETTERS_ELSEWHERE = 14
        private const val MIN_LETTERS_AFTER_BASMALA = 6
        /** The share of its letters a place must hold, what it must have over the next place's, and the words of the text it must say whole. */
        const val MIN_SCORE = 0.8f
        const val MARGIN = 0.04f
        private const val MIN_WHOLE_WORDS = 2
        /** The share of the stretch's own letters the phrase must say (its letters scattered over a longer stretch are not that stretch). */
        private const val MIN_TIGHT = 0.6f
        /** Words that aren't the text's set aside at a phrase's beginning, at most. */
        private const val MAX_STRAY_WORDS = 4
        /** What is kept of the utterances before, to look for with the one being said; and how far back they may take the place. */
        private const val KEPT_UTTERANCES = 3
        private const val KEPT_WORDS = 40
        private const val MAX_AYAT_BACK = 4
        /** Words of a sūra's opening that may have been said as what isn't the text's, at most. */
        private const val MAX_OPENING_WORDS = 6
        private val FATIHA = AyahRef(1, 1)
        private val FATIHA_SECOND = AyahRef(1, 2)
        private val ISTIADHA_WORDS = Arabic.words("أعوذ بالله من الشيطان الرجيم").size
        private val ISTIADHA = Arabic.skeleton("أعوذ بالله من الشيطان الرجيم")
        private val BASMALA = Arabic.words("بسم الله الرحمن الرحيم").map { Arabic.skeleton(it) }
        private val BASMALA_LETTERS = BASMALA.joinToString("")
        /** Letters a formula may be heard with beyond its own, and still be it. */
        private const val FORMULA_SLACK = 4
    }
}
