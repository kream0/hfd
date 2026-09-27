package app.hfd.core.quran

import kotlinx.serialization.Serializable

/** One sūra's metadata, from Tanzil's quran-data.xml (see tools/fetch-data.sh). */
@Serializable
data class SuraMeta(
    val index: Int,
    val ayas: Int,
    /** Index of its first āya in the whole muṣḥaf (0-based). */
    val start: Int = 0,
    /** Arabic name, e.g. "البقرة". */
    val name: String,
    /** Transliteration, e.g. "Al-Baqara". */
    val tname: String,
    /** English meaning, e.g. "The Cow". */
    val ename: String,
    val type: String = "",
    val order: Int = 0,
)

@Serializable
data class SuraFile(val schema: Int = 1, val source: String = "", val suras: List<SuraMeta>)

/**
 * Reader for Tanzil's "text with aya numbers" files (`sura|aya|text` per line, then a `#`
 * comment block with the licence). Used for the Uthmani text and the translations.
 */
object Tanzil {
    const val TOTAL_AYAT = 6236

    private const val SHADDA = '\u0651'

    /** Tanzil's copyright block (the `#` lines), which must travel with every copy of the text. */
    fun notice(lines: List<String>): String =
        lines.filter { it.startsWith("#") }.joinToString("\n") { it.trimEnd('\r') }

    /** sura → āyāt texts (index 0 = āya 1). Throws on malformed input. */
    fun parse(lines: Sequence<String>): Map<Int, List<String>> {
        val out = HashMap<Int, MutableList<String>>()
        for (raw in lines) {
            val line = raw.trimEnd('\r', '\n')
            if (line.isEmpty() || line.startsWith("#")) continue
            val a = line.indexOf('|')
            val b = line.indexOf('|', a + 1)
            require(a > 0 && b > a) { "Malformed line: ${line.take(40)}" }
            val sura = line.substring(0, a).toInt()
            val aya = line.substring(a + 1, b).toInt()
            val list = out.getOrPut(sura) { mutableListOf() }
            require(aya == list.size + 1) { "Out of order: $sura:$aya" }
            list += line.substring(b + 1)
        }
        return out
    }

    /**
     * Splits Tanzil's first-āya line into (basmala, āya text), the way Tanzil's own XML stores
     * them (`bismillah` attribute + text). [basmala] is al-Fātiḥa's first āya as Tanzil writes
     * it (in two sūras Tanzil writes its first letter with a shadda). The characters themselves
     * are untouched.
     */
    fun splitBasmala(sura: Int, aya: Int, text: String, basmala: String): Pair<String?, String> {
        if (aya != 1 || sura == 1 || sura == 9) return null to text
        val want = basmala.split(' ')
        val words = text.split(' ')
        if (words.size <= want.size) return null to text
        val head = words.subList(0, want.size)
        val same = head.drop(1) == want.drop(1) && head[0].replace(SHADDA.toString(), "") == want[0].replace(SHADDA.toString(), "")
        if (!same) return null to text
        return head.joinToString(" ") to words.drop(want.size).joinToString(" ")
    }
}

/**
 * The Qur'an text, per āya, with the basmala of each sūra kept apart (see [Tanzil.splitBasmala]).
 * Serialized as the compact JSON the app caches after first launch.
 */
@Serializable
data class QuranText(
    val schema: Int = SCHEMA,
    /** SHA-256 of the Tanzil file this was built from. */
    val source: String = "",
    /** suras[s - 1][a - 1] = text of āya s:a. */
    val suras: List<List<String>>,
    /** Sūra number (as a string key) → its basmala as written in Tanzil's text. */
    val basmala: Map<String, String> = emptyMap(),
    /** Tanzil's copyright notice, kept with this derived copy as its terms require. */
    val notice: String = "",
) {
    val ayahCount: Int get() = suras.sumOf { it.size }

    fun text(ref: AyahRef): String? = suras.getOrNull(ref.sura - 1)?.getOrNull(ref.aya - 1)

    fun basmalaOf(sura: Int): String? = basmala[sura.toString()]

    companion object {
        const val SCHEMA = 1

        fun fromTanzil(lines: List<String>, source: String = "", splitBasmala: Boolean = true): QuranText {
            val parsed = Tanzil.parse(lines.asSequence())
            val fatiha1 = parsed[1]?.firstOrNull() ?: error("Missing al-Fātiḥa")
            val basmala = HashMap<String, String>()
            val suras = (1..parsed.size).map { s ->
                val ayat = parsed[s] ?: error("Missing sūra $s")
                if (!splitBasmala) ayat else ayat.mapIndexed { i, text ->
                    val (b, rest) = Tanzil.splitBasmala(s, i + 1, text, fatiha1)
                    if (b != null) basmala[s.toString()] = b
                    rest
                }
            }
            return QuranText(SCHEMA, source, suras, basmala, Tanzil.notice(lines))
        }
    }
}
