package app.hfd.core.tajweed

import app.hfd.core.Assets
import app.hfd.core.quran.AyahRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The whole Qur'an's tajwīd as [Tajweed] reads it, against the annotation the coloured editions
 * online share (alquran.cloud's "quran-tajweed": `[code[letters]`), word by word. Not a test: it
 * prints where the two differ, to be read by someone who knows the rules. Run with
 * HFD_TAJWEED_REF=<the edition's JSON> (tools/tajweed/compare.sh fetches it); HFD_TAJWEED_OUT
 * names the file the differences go to.
 */
class TajweedReferenceBench {
    private val codes = mapOf(
        Rule.GHUNNAH to 'g', Rule.IKHFA to 'f', Rule.IKHFA_SHAFAWI to 'c', Rule.IQLAB to 'i', Rule.IDGHAM_GHUNNAH to 'a',
        Rule.IDGHAM_NO_GHUNNAH to 'u', Rule.IDGHAM_SHAFAWI to 'w', Rule.IDGHAM_MUTAJANISAYN to 'd', Rule.IDGHAM_MUTAQARIBAYN to 'b',
        Rule.IDGHAM_MUTAMATHILAYN to 'x', Rule.QALQALAH to 'q', Rule.MADD_NATURAL to 'n', Rule.MADD_MUNFASIL to 'o', Rule.MADD_MUTTASIL to 'o',
        Rule.MADD_LAZIM to 'm', Rule.MADD_ARID to 'p', Rule.MADD_LIN to 'p', Rule.HAMZAT_WASL to 'h', Rule.LAM_SHAMSIYYAH to 'l', Rule.SILENT to 's',
        Rule.TAFKHIM to 't',
    )

    private fun letter(c: Char) = c in 'ء'..'غ' || c in 'ف'..'ي' || c in 'ٱ'..'ٳ'

    @Test
    fun compare() {
        val path = System.getenv("HFD_TAJWEED_REF")
        assumeTrue(path != null)
        val root = Json.parseToJsonElement(File(path!!).readText()).jsonObject["data"]!!.jsonObject["surahs"]!!.jsonArray
        val out = StringBuilder()
        val both = HashMap<Char, Int>()
        val mine = HashMap<Char, Int>()
        val theirs = HashMap<Char, Int>()
        val doubts = ArrayList<String>()
        var wordsDiffer = 0
        val mark = Regex("\\[([a-z])(?::\\d+)?\\[")
        for ((s, sura) in root.withIndex()) {
            for ((a, aya) in sura.jsonObject["ayahs"]!!.jsonArray.withIndex()) {
                val ref = AyahRef(s + 1, a + 1)
                val text = Assets.quran.text(ref)!!
                Tajweed.doubts(text).forEach { doubts += "$ref $it" }
                // The reference: its rules by the word they start in.
                val raw = aya.jsonObject["text"]!!.jsonPrimitive.content
                val plain = StringBuilder()
                val starts = ArrayList<Pair<Char, Int>>()
                var k = 0
                var open = 0
                while (k < raw.length) {
                    val m = if (raw[k] == '[') mark.matchAt(raw, k) else null
                    when {
                        m != null -> { starts += m.groupValues[1][0] to plain.length; open++; k = m.range.last + 1 }
                        raw[k] == ']' && open > 0 -> { open--; k++ }
                        else -> plain.append(raw[k++])
                    }
                }
                val refWords = ArrayList<IntRange>()
                var i = 0
                while (i < plain.length) {
                    if (plain[i] == ' ') { i++; continue }
                    var e = i
                    while (e < plain.length && plain[e] != ' ') e++
                    if ((i until e).any { letter(plain[it]) }) refWords += i until e
                    i = e
                }
                val myWords = text.split(' ').filter { w -> w.any { letter(it) } }
                if (refWords.size != myWords.size) { wordsDiffer++; out.append("$ref WORDS ${myWords.size} / ${refWords.size}\n"); continue }
                val want = starts.map { (code, pos) -> code to refWords.indexOfFirst { pos in it || pos == it.last + 1 } }.groupingBy { it }.eachCount()
                val have = Tajweed.annotate(text)
                    .filter { it.part == Part.DROPPED || it.rule !in setOf(Rule.IDGHAM_GHUNNAH, Rule.IDGHAM_SHAFAWI) }
                    .map { codes.getValue(it.rule) to it.word }.groupingBy { it }.eachCount()
                for (key in want.keys + have.keys) {
                    val w = want[key] ?: 0
                    val h = have[key] ?: 0
                    both.merge(key.first, minOf(w, h), Int::plus)
                    if (h > w) { mine.merge(key.first, h - w, Int::plus); if (key.first != 'n' && key.first != 't') out.append("$ref ${key.first} MINE  ${myWords.getOrNull(key.second)}   | ${plain.substring(refWords[key.second].first, refWords[key.second].last + 1)}\n") }
                    if (w > h) { theirs.merge(key.first, w - h, Int::plus); out.append("$ref ${key.first} THEIRS ${myWords.getOrNull(key.second)}   | ${plain.substring(refWords[key.second].first, refWords[key.second].last + 1)}\n") }
                }
            }
        }
        val summary = StringBuilder("code both mine-only theirs-only\n")
        for (c in (both.keys + mine.keys + theirs.keys).sorted()) summary.append("$c ${both[c] ?: 0} ${mine[c] ?: 0} ${theirs[c] ?: 0}\n")
        summary.append("āyāt whose words differ: $wordsDiffer\ndoubts: ${doubts.size}\n")
        doubts.groupingBy { it.substringAfter(' ').substringBefore(" in ").substringBefore(" ending").substringBefore(" opening") }.eachCount().entries.sortedByDescending { it.value }.take(40).forEach { summary.append("  ${it.value} × ${it.key}\n") }
        File(System.getenv("HFD_TAJWEED_OUT") ?: "build/tajweed.txt").apply { parentFile?.mkdirs() }.writeText(summary.toString() + "\n" + doubts.joinToString("\n") + "\n\n" + out)
        println(summary)
    }

    /** HFD_TAJWEED_DUMP="1:1 2:255": each word of those āyāt with its rules; "ra" lists every rāʾ word once with its ruling. */
    @Test
    fun dump() {
        val what = System.getenv("HFD_TAJWEED_DUMP")
        assumeTrue(what != null)
        val out = StringBuilder()
        if (what == "ra" || what == "name") {
            val seen = HashMap<String, Int>()
            for (s in 1..114) for (a in 1..Assets.quran.suras[s - 1].size) {
                val text = Assets.quran.text(AyahRef(s, a))!!
                val words = text.split(' ').filter { w -> w.any { letter(it) } }
                val spans = Tajweed.annotate(text)
                words.forEachIndexed { i, w ->
                    val wanted = if (what == "ra") '\u0631' in w else w.contains("\u0644\u0644\u0651\u064E\u0647") || w.contains("\u0644\u0650\u0644\u0651\u064E\u0647")
                    if (!wanted) return@forEachIndexed
                    val last = i == words.lastIndex
                    val heavy = spans.count { it.word == i && it.rule == Rule.TAFKHIM }
                    val key = (if (what == "name") (words.getOrNull(i - 1) ?: "^") + " " else "") + w + (if (last) " ◌" else "") + " → " + heavy
                    seen.merge(key, 1, Int::plus)
                }
            }
            seen.entries.sortedByDescending { it.value }.forEach { out.append("${it.value}\t${it.key}\n") }
        } else if (what!!.startsWith("beats:")) {
            // For the timing study (tools/tajweed): each āya's text and its beats, a line each.
            for (r in what.removePrefix("beats:").split(' ')) {
                val ref = AyahRef.parse(r)!!
                val text = Assets.quran.text(ref)!!
                out.append("$ref\t$text\t" + Tajweed.beats(text).joinToString(" ") { b -> "${b.at}:${b.vowel.name[0]}:${b.madd?.name ?: "-"}:${if (b.doubled) 1 else 0}:${b.nasal?.name ?: "-"}:${if (b.tanwin) 1 else 0}:${if (b.last) 1 else 0}:${b.word}" } + "\n")
            }
        } else for (r in what.split(' ')) {
            val ref = AyahRef.parse(r)!!
            val text = Assets.quran.text(ref)!!
            val words = text.split(' ').filter { w -> w.any { letter(it) } }
            val spans = Tajweed.annotate(text)
            out.append("$ref\n")
            words.forEachIndexed { i, w -> out.append("  $i $w  " + spans.filter { it.word == i }.joinToString(" ") { "${it.rule}${if (it.part == Part.DROPPED) "-" else ""}[${text.substring(it.from, it.to)}]" } + "\n") }
        }
        File(System.getenv("HFD_TAJWEED_OUT") ?: "build/tajweed-dump.txt").apply { parentFile?.mkdirs() }.writeText(out.toString())
    }
}
