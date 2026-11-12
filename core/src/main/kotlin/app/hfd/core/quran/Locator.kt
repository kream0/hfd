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
            val a = try2(said.takeLast(back).flatten() + now, final)
            val pick = a?.pick
            val had = best?.pick
            when {
                pick != null && (had == null || fits(pick, had)) -> { best = a; used = back }
                had != null -> break
                listed.isEmpty() && a != null -> listed = a.hits
            }
        }
        val a = best
        val pick = a?.pick
        if (a == null || pick == null) {
            steady = null
            if (listed.isNotEmpty() || final) candidates = listed
            return null
        }
        // Said just before the place found, and not the text's (in the same breath, or the
        // utterance before): a sūra's opening letters, if they are what lies before the place.
        // It is then theirs, with their sound in front of the phrase.
        val earlier = said.getOrNull(said.size - 1 - used).orEmpty()
        val sound = a.before.ifEmpty { earlier }
        val start = if (sound.isNotEmpty()) lettersBefore(pick) else null
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
        fun begins(p: List<String>, from: Int): Boolean {
            val w = skeletons.drop(from)
            return w.isNotEmpty() && w.size <= p.size && (0 until w.size - 1).all { w[it] == p[it] } && p[w.size - 1].startsWith(w.last())
        }
        fun starts(p: List<String>, from: Int): Boolean = skeletons.size >= from + p.size && p.indices.all { skeletons[from + it] == p[it] }
        // Still saying the isti'ādha or the basmala: nothing of the text yet.
        if (begins(ISTIADHA, 0)) return null
        var from = if (starts(ISTIADHA, 0)) ISTIADHA.size else 0
        if (begins(BASMALA, from)) return null
        val basmala = starts(BASMALA, from)
        if (keepBasmala) {
            // Al-Fātiḥa, if its second āya is what follows the basmala (among the places that say it).
            if (!basmala) return null
            val rest = all.drop(from + BASMALA.size)
            if (letters(rest) < MIN_LETTERS_AFTER_BASMALA) return null
            val hits = text.recited(rest.joinToString(" "))
            val top = hits.firstOrNull() ?: return null
            val second = hits.firstOrNull { top.score - it.score < MARGIN && it.ref == FATIHA_SECOND && it.word == 0 } ?: return null
            if (second.score < MIN_SCORE) return null
            return Attempt(emptyList(), all.drop(from), true, hits, QuranSearch.Recited(FATIHA, 0, second.last, second.score, second.whole + BASMALA.size))
        }
        if (basmala) from += BASMALA.size
        // What isn't a word of the text, at the beginning; and at the end of a phrase still being said (a word half said).
        var first = from
        while (first < all.size && first - from < MAX_STRAY_WORDS && !text.known(all[first])) first++
        var end = all.size
        if (!final && end > first + 1 && !text.known(all[end - 1])) end--
        while (true) {
            val phrase = all.subList(first, end)
            if (letters(phrase) < minLetters) return Attempt(all.subList(from, first), phrase, false)
            val hits = text.recited(phrase.joinToString(" "))
            val pick = pick(hits, phrase.size)
            // The place must begin with the phrase's first word. A word that is another's there (a
            // few of its letters found among the words before: half a word, a breath read as one)
            // is set aside, and the place has to stand out without it. («ممة وهم لا يشعرون» was found
            // in Yūsuf alone, by بأمرهم هذا before the three words that eight āyāt end with.)
            if (pick != null && phrase.size > 1 && first - from < MAX_STRAY_WORDS && !alike(Arabic.skeleton(phrase[0]), text.wordAt(pick.ref, pick.word))) {
                first++
                continue
            }
            return Attempt(all.subList(from, first), phrase, false, hits, pick)
        }
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

    /** The place among [hits] that stands out, if one does. */
    private fun pick(hits: List<QuranSearch.Recited>, words: Int): QuranSearch.Recited? {
        val best = hits.firstOrNull() ?: return null
        if (best.score < MIN_SCORE || best.tight < MIN_TIGHT || best.whole < minOf(MIN_WHOLE_WORDS, words)) return null
        val close = hits.filter { best.score - it.score < MARGIN }
        if (close.size == 1) return best
        // Several places say it as well: the one in the sūra the reciter is in, the first after where they are.
        val n = near ?: return null
        val there = close.filter { it.ref.sura == n.sura && it.whole >= minOf(MIN_WHOLE_WORDS, words) }
        return there.firstOrNull { it.ref >= n } ?: there.firstOrNull()
    }

    /** Whether the place a longer stretch found ([longer]) is the one its end alone found ([shorter]), from further back. */
    private fun fits(longer: QuranSearch.Recited, shorter: QuranSearch.Recited): Boolean =
        longer.ref.sura == shorter.ref.sura && longer.ref <= shorter.ref && longer.last >= shorter.ref && shorter.ref.aya - longer.ref.aya <= MAX_AYAT_BACK

    /** The sūra's first āya, if only its opening letters lie between the sūra's start and [pick]. */
    private fun lettersBefore(pick: QuranSearch.Recited): AyahRef? {
        val first = AyahRef(pick.ref.sura, 1)
        if (!Tracker.isLetters(first)) return null
        // (Letters that open a longer āya: الٓر تِلْكَ…)
        if (pick.ref == first) return if (pick.word == 1) first else null
        if (pick.word != 0) return null
        val between = (1 until pick.ref.aya).map { AyahRef(pick.ref.sura, it) }
        return if (between.all { Tracker.isLetters(it) && text.wordCount(it) == 1 }) first else null
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
        private val FATIHA = AyahRef(1, 1)
        private val FATIHA_SECOND = AyahRef(1, 2)
        private val ISTIADHA = Arabic.words("أعوذ بالله من الشيطان الرجيم").map { Arabic.skeleton(it) }
        private val BASMALA = Arabic.words("بسم الله الرحمن الرحيم").map { Arabic.skeleton(it) }
    }
}
