package app.hfd.core.quran

import app.hfd.core.recite.Arabic
import java.text.Normalizer
import kotlin.math.ln

/**
 * Finding one's way in the whole Qur'an: by a reference (2:255, "baqara 255"), a sūra's name or
 * number, a juzʾ or a ḥizb, words of the text (typed in Arabic, however spelt: compared without
 * vowel signs nor alif forms, as recitations are), words of a translation, or a phrase recited
 * aloud as a recogniser wrote it ([recited]).
 */
class QuranSearch(private val suras: List<SuraMeta>, private val quran: QuranText, private val parts: Parts) {

    /** Words of an āya found in the text: its words [from]‥[to] (as [Arabic.words] counts them). */
    data class TextHit(val ref: AyahRef, val from: Int, val to: Int)

    /** A passage of a translation: characters [from] until [to] of the āya's translation. */
    data class TranslationHit(val ref: AyahRef, val from: Int, val to: Int)

    /** A juzʾ or a ḥizb, and where it begins. */
    data class PartHit(val juz: Boolean, val number: Int, val start: AyahRef)

    class Results(
        /** Āyāt the query names (2:255; "baqara 255" for each sūra the name fits). */
        val references: List<AyahRef> = emptyList(),
        /** Sūras by name or number. */
        val suras: List<Int> = emptyList(),
        val parts: List<PartHit> = emptyList(),
        /** The first matches in the text, in the muṣḥaf's order, and how many there are in all. */
        val text: List<TextHit> = emptyList(),
        val textCount: Int = 0,
        val translation: List<TranslationHit> = emptyList(),
        val translationCount: Int = 0,
    ) {
        val isEmpty: Boolean get() = references.isEmpty() && suras.isEmpty() && parts.isEmpty() && text.isEmpty() && translation.isEmpty()
    }

    /**
     * Where a recited phrase is in the text: from word [word] of āya [ref] to āya [last]; [score] of
     * its letters found (0‥1); [whole]: the text's words there it says letter for letter; [tight]:
     * the letters found over those of the stretch itself, when it is longer than the phrase (a
     * phrase whose letters are found scattered over twice as many words is not said there).
     */
    data class Recited(val ref: AyahRef, val word: Int, val last: AyahRef, val score: Float, val whole: Int = 0, val tight: Float = 1f)

    /** Whether [word], as heard, is a word of the text (by its letters). */
    fun known(word: String): Boolean = occurrences.containsKey(Arabic.skeleton(word))

    /** Word [word] of āya [ref], as it is compared (its letters); null past the text's end. */
    fun wordAt(ref: AyahRef, word: Int): String? = ayaIndex[ref]?.let { words.getOrNull(ayaStart[it] + word) }

    /** The words of āya [ref]. */
    fun wordCount(ref: AyahRef): Int {
        val i = ayaIndex[ref] ?: return 0
        return (if (i + 1 < ayaStart.size) ayaStart[i + 1] else words.size) - ayaStart[i]
    }

    // ------------------------------------------------------------------ the text, as it is compared

    private class Aya(val ref: AyahRef, val skeleton: String)

    /** Every āya, its words' skeletons joined by a space. */
    private val ayat: List<Aya> by lazy {
        suras.flatMap { s -> (1..s.ayas).map { a -> AyahRef(s.index, a).let { Aya(it, skeletons(quran.text(it).orEmpty()).joinToString(" ")) } } }
    }

    /** Every word of the text in order: its skeleton, and its āya (an index in [ayat]). */
    private val words: List<String> by lazy { ayat.flatMap { it.skeleton.split(' ').filter(String::isNotEmpty) } }
    private val wordAya: IntArray by lazy {
        val out = IntArray(words.size)
        var w = 0
        ayat.forEachIndexed { i, a -> repeat(a.skeleton.split(' ').count(String::isNotEmpty)) { out[w++] = i } }
        out
    }
    /** The first word of each āya, in [words]. */
    private val ayaStart: IntArray by lazy {
        val out = IntArray(ayat.size)
        var w = 0
        ayat.forEachIndexed { i, a -> out[i] = w; w += a.skeleton.split(' ').count(String::isNotEmpty) }
        out
    }
    private val occurrences: Map<String, IntArray> by lazy {
        val lists = HashMap<String, ArrayList<Int>>()
        words.forEachIndexed { i, w -> lists.getOrPut(w) { ArrayList() }.add(i) }
        lists.mapValues { it.value.toIntArray() }
    }

    private val ayaIndex: Map<AyahRef, Int> by lazy { ayat.withIndex().associate { it.value.ref to it.index } }

    private fun skeletons(text: String): List<String> = Arabic.words(text).map { Arabic.skeleton(it) }

    /** Builds what the searches compare against (a moment's work, once): call it off the main thread. */
    fun prepare() {
        occurrences.size
        wordAya.size
        ayaStart.size
    }

    // ------------------------------------------------------------------ typed

    /**
     * What [query] finds; [translation] is searched too when the query is in Latin letters. At
     * most [limit] matches of the text and of the translation each (their counts are whole).
     */
    fun search(query: String, translation: QuranText? = null, limit: Int = 50): Results {
        val q = digits(query).trim()
        if (q.isEmpty()) return Results()
        val references = ArrayList<AyahRef>()
        val found = LinkedHashSet<Int>()
        val partHits = ArrayList<PartHit>()

        // 2:255, 2 255, 2.255 …
        REFERENCE.matchEntire(q)?.let { m ->
            valid(m.groupValues[1].toInt(), m.groupValues[2].toInt())?.let { references += it }
        }
        // "juz 5", "جزء 5", "hizb 12", "الحزب 12".
        val named = PART.matchEntire(q)
        if (named != null) {
            val juz = named.groupValues[1].let { it.contains("جز") || it.startsWith("j", ignoreCase = true) || it.startsWith("dj", ignoreCase = true) }
            part(juz, named.groupValues[2].toInt())?.let { partHits += it }
        } else {
            // "baqara 255", "البقرة 255": an āya of each sūra the name fits.
            NAMED.matchEntire(q)?.let { m ->
                val aya = m.groupValues[2].toInt()
                for (s in named(m.groupValues[1]).take(3)) valid(s, aya)?.let { references += it }
            }
        }
        if (q.all { it.isDigit() }) {
            // A number: the sūra first, then the sūras that begin with it, the juzʾ and the ḥizb.
            val n = q.toIntOrNull()
            if (n != null && n in 1..suras.size) found += n
            suras.filter { it.index.toString().startsWith(q) }.forEach { found += it.index }
            if (n != null) {
                part(true, n)?.let { partHits += it }
                part(false, n)?.let { partHits += it }
            }
        } else if (references.isEmpty() && partHits.isEmpty()) {
            found += named(q)
        }

        var text = emptyList<TextHit>()
        var textCount = 0
        val arabic = skeletons(q).joinToString(" ")
        if (arabic.replace(" ", "").length >= MIN_LETTERS) {
            val hits = ArrayList<TextHit>()
            for (a in ayat) {
                val at = a.skeleton.indexOf(arabic)
                if (at < 0) continue
                textCount++
                if (hits.size < limit) {
                    val from = a.skeleton.take(at).count { it == ' ' }
                    hits += TextHit(a.ref, from, from + arabic.count { it == ' ' })
                }
            }
            text = hits
        }

        var translated = emptyList<TranslationHit>()
        var translatedCount = 0
        val latin = plain(q)
        if (translation != null && arabic.isEmpty() && latin.count { it.isLetter() } >= MIN_LATIN && references.isEmpty() && partHits.isEmpty()) {
            val hits = ArrayList<TranslationHit>()
            for (a in ayat) {
                val t = translation.text(a.ref) ?: continue
                val at = plain(t).indexOf(latin)
                if (at < 0) continue
                translatedCount++
                if (hits.size < limit) hits += TranslationHit(a.ref, at, at + latin.length)
            }
            translated = hits
        }
        return Results(references.distinct(), found.toList(), partHits, text, textCount, translated, translatedCount)
    }

    private fun valid(sura: Int, aya: Int): AyahRef? = suras.getOrNull(sura - 1)?.takeIf { aya in 1..it.ayas }?.let { AyahRef(sura, aya) }

    private fun part(juz: Boolean, n: Int): PartHit? =
        (if (juz) parts.juz else parts.hizb).getOrNull(n - 1)?.let { PartHit(juz, n, it) }

    /** Sūras whose name (transliterated, in English or in Arabic) holds [name]. */
    private fun named(name: String): List<Int> {
        val q = fold(name)
        if (q.isEmpty()) return emptyList()
        val arabic = Arabic.skeleton(name)
        // Where the name begins with it first ("nur": An-Nūr before Al-Munāfiqūn), then in order.
        return suras.mapNotNull { s ->
            val at = listOf(fold(s.tname).indexOf(q), fold(s.ename).indexOf(q)).filter { it >= 0 }.minOrNull()
                ?: (if (arabic.length >= 2) Arabic.skeleton(s.name).indexOf(arabic).takeIf { it >= 0 } else null)
                ?: return@mapNotNull null
            s.index to at
        }.sortedWith(compareBy({ it.second }, { it.first })).map { it.first }
    }

    // ------------------------------------------------------------------ recited

    /**
     * Where [heard] (a recogniser's writing of a phrase recited: any spelling) is in the text,
     * best first. Each of its words votes for the places it occurs at (rare words louder); the
     * likeliest places are then compared letter by letter, and kept if most of the phrase is there.
     */
    fun recited(heard: String, limit: Int = 8): List<Recited> {
        val h = heard.split(Regex("\\s+")).map { Arabic.skeleton(it) }.filter { it.isNotEmpty() }
        // A phrase caught on the way begins or ends inside a word (or with a breath taken for
        // one): if the whole of it is nowhere, it is looked for without its first or last word.
        val tries = listOf(h, h.dropLast(1), h.drop(1), h.drop(1).dropLast(1))
        for ((i, words) in tries.withIndex()) {
            if (i > 0 && words.size < MIN_WORDS_CUT) continue
            val found = places(words, limit)
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    private fun places(h: List<String>, limit: Int): List<Recited> {
        val letters = h.joinToString("")
        if (letters.length < MIN_RECITED_LETTERS) return emptyList()
        val votes = HashMap<Int, Double>()
        h.forEachIndexed { i, w ->
            val at = occurrences[w] ?: return@forEachIndexed
            val weight = ln(words.size.toDouble() / at.size) + 1
            for (p in at) votes.merge(p - i, weight, Double::plus)
        }
        // A word misheard or dropped shifts what follows by one: neighbouring starts are one place.
        val places = votes.entries.sortedByDescending { e -> e.value + (votes[e.key - 1] ?: 0.0) + (votes[e.key + 1] ?: 0.0) }.take(maxOf(CANDIDATES, 3 * limit))
        val out = ArrayList<Recited>()
        for ((start, _) in places) {
            val from = (start - SLACK).coerceIn(0, words.size)
            val to = (start + h.size + SLACK).coerceIn(0, words.size)
            if (to <= from) continue
            val match = align(letters, from, to) ?: continue
            if (match.same < MIN_RECITED_LETTERS || match.same < RECITED_FRACTION * letters.length) continue
            val first = ayat[wordAya[match.first]]
            out += Recited(first.ref, match.first - ayaStart[wordAya[match.first]], ayat[wordAya[match.last]].ref, match.same.toFloat() / letters.length, match.whole, match.same.toFloat() / maxOf(letters.length, match.span))
        }
        return out.sortedWith(compareByDescending<Recited> { it.score }.thenBy { it.ref })
            .distinctBy { it.ref }
            .take(limit)
    }

    private class Match(val first: Int, val last: Int, val same: Int, val whole: Int, val span: Int)

    /** [heard]'s letters against words [from] until [to]: the stretch of them it says, and how many of its letters are there. */
    private fun align(heard: String, from: Int, to: Int): Match? {
        val e = StringBuilder()
        val owner = ArrayList<Int>()
        for (w in from until to) {
            e.append(words[w])
            repeat(words[w].length) { owner += w }
        }
        val n = e.length
        val m = heard.length
        if (n == 0 || m == 0) return null
        // The whole phrase against any stretch of the window: letters matched earn 2, a wrong or
        // missing one costs 1 (as the recitation's tracker scores).
        val d = Array(n + 1) { IntArray(m + 1) }
        for (j in 1..m) d[0][j] = -j
        for (i in 1..n) for (j in 1..m) {
            val diag = d[i - 1][j - 1] + if (e[i - 1] == heard[j - 1]) 2 else -1
            d[i][j] = maxOf(diag, d[i - 1][j] - 1, d[i][j - 1] - 1)
        }
        var best = 1
        for (i in 1..n) if (d[i][m] > d[best][m]) best = i
        var i = best
        var j = m
        var same = 0
        // How many of each word's letters the phrase says.
        val said = IntArray(to - from)
        while (i > 0 && j > 0) {
            val eq = e[i - 1] == heard[j - 1]
            when {
                d[i][j] == d[i - 1][j - 1] + (if (eq) 2 else -1) -> {
                    if (eq) { same++; said[owner[i - 1] - from]++ }
                    i--; j--
                }
                d[i][j] == d[i - 1][j] - 1 -> i--
                else -> j--
            }
        }
        // The stretch is from the first word mostly said to the last: a few letters of the word
        // before (what was heard before the phrase, a breath, half a word) are not its beginning.
        // (A sūra's first words were found in the last āya of the sūra before.)
        fun mostly(w: Int) = said[w - from] * 2 >= words[w].length
        val first = (from until to).firstOrNull(::mostly) ?: return null
        val last = (from until to).last(::mostly)
        return Match(first, last, same, (first..last).count { said[it - from] == words[it].length }, (first..last).sumOf { words[it].length })
    }

    companion object {
        /** Letters (without vowel signs or alifs) a typed Arabic query needs. */
        const val MIN_LETTERS = 2
        /** Letters a query needs to be looked for in a translation. */
        const val MIN_LATIN = 3
        /** Letters a recited phrase needs, and the share of them a place must hold. */
        const val MIN_RECITED_LETTERS = 5
        const val RECITED_FRACTION = 0.7
        /** Words a phrase must keep when it is looked for without its first or last one. */
        const val MIN_WORDS_CUT = 3
        private const val CANDIDATES = 24
        private const val SLACK = 3

        private val REFERENCE = Regex("""(\d{1,3})\s*[:.,;/\-\s]\s*(\d{1,3})""")
        private val NAMED = Regex("""(\D.*?)[\s:,]+(\d{1,3})""")
        private val PART = Regex("""(juz\S*|jouz\S*|djouz\S*|hizb\S*|ḥizb\S*|(?:ال)?جز\S*|(?:ال)?حزب\S*)\s*(\d{1,2})""", RegexOption.IGNORE_CASE)

        /** Eastern Arabic and Persian digits as 0‥9. */
        fun digits(s: String): String = buildString {
            for (c in s) append(
                when (c) {
                    in '٠'..'٩' -> '0' + (c - '٠')
                    in '۰'..'۹' -> '0' + (c - '۰')
                    else -> c
                },
            )
        }

        /** Letters and digits only, lower case, without diacritics: "al-Baqara" and "baqara" both find البقرة. */
        fun fold(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFD).filter { it.isLetterOrDigit() && it != 'ʿ' && it != 'ʾ' }.lowercase()

        private val BASE = CharArray(0x250) { c ->
            val d = Normalizer.normalize(c.toChar().toString(), Normalizer.Form.NFD)
            (d.firstOrNull() ?: c.toChar()).lowercaseChar()
        }

        /**
         * [s] in lower case with each accented letter as its plain one, character for character
         * (a match's place in it is its place in [s]); typographic apostrophes and spaces plain.
         */
        fun plain(s: String): String = buildString(s.length) {
            for (c in s) append(
                when {
                    c == '’' || c == 'ʼ' || c == '‘' -> '\''
                    c == ' ' || c == ' ' -> ' '
                    c.code < BASE.size -> BASE[c.code]
                    else -> c.lowercaseChar()
                },
            )
        }
    }
}
