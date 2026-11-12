package app.hfd.core.quran

import app.hfd.core.Assets
import app.hfd.core.HfdJson
import app.hfd.core.ctc.CtcRecognizer
import app.hfd.core.ctc.OrtCtcModel
import app.hfd.core.recite.Clarity
import app.hfd.core.recite.Segmenter
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Finding where a recitation is, as the app does it ([Locator]), on recitations taken anywhere in
 * the Qur'an (HFD_LOCATE: tools/locate/sample.py + fetch.sh; HFD_CTC: the recogniser's ONNX file):
 * each is streamed through [Segmenter], every reading of an utterance as it grows is given to the
 * locator, and the place it settles on is compared with the āya recited. A place taken at random
 * is heard from its āya's first word ("start") and from two fifths into that āya, cut wherever
 * that falls ("inside"); a sūra's beginning, as recorded and after the reciter's basmala. Writes
 * <HFD_LOCATE>/locate.txt: per kind the places right, wrong and not found, and how many seconds
 * of recitation it took. Not run without those two variables.
 */
class LocateBench {
    private class Case(val id: String, val reciter: String, val refs: List<AyahRef>, val kind: String)

    @Test
    fun bench() {
        val dir = System.getenv("HFD_LOCATE")?.takeIf { it.isNotBlank() }
        val model = System.getenv("HFD_CTC")?.takeIf { it.isNotBlank() }
        Assume.assumeTrue("HFD_LOCATE and HFD_CTC not set", dir != null && model != null)
        val only = System.getenv("HFD_LOCATE_ONLY")?.takeIf { it.isNotBlank() }?.let(::Regex)
        val parts = Parts(HfdJson.decodeFromString(PartsFile.serializer(), Assets.file("quran/parts.json").readText()))
        val search = QuranSearch(SuraNames.apply(Assets.suras), Assets.quran, parts)
        search.prepare()
        val cases = File(dir, "cases.tsv").readLines().filter { it.isNotBlank() }.map { l ->
            val f = l.split('\t')
            Case(f[0], f[1], f[2].split(',').map { AyahRef.parse(it)!! }, f[3])
        }.filter { only == null || only.containsMatchIn(it.id) }.let { all ->
            // HFD_LOCATE_SHARD "k/n": every n-th case from the k-th (CI runs the parts at once).
            val shard = System.getenv("HFD_LOCATE_SHARD")?.takeIf { it.isNotBlank() }?.split('/')?.map(String::toInt)
            if (shard == null) all else all.filterIndexed { i, _ -> i % shard[1] == shard[0] }
        }
        val out = StringBuilder()
        val totals = LinkedHashMap<String, MutableList<Double?>>() // per kind: seconds when right, -1 wrong, null not found
        val offered = HashMap<String, Int>()
        OrtCtcModel(model!!, 4).use { ort ->
            val recognizer by lazy { CtcRecognizer(ort) }
            for (c in cases) {
                val sounds = c.refs.map { read(File(dir, "${c.reciter}/%03d%03d.f32".format(it.sura, it.aya))) }
                val whole = join(sounds)
                val variants = if (c.kind == "opening") {
                    listOf("opening" to whole, "opening after the basmala" to join(listOf(read(File(dir, "${c.reciter}/001001.f32"))) + sounds))
                } else {
                    listOf("start" to whole, "inside" to whole.copyOfRange(sounds[0].size * 2 / 5, whole.size))
                }
                for ((kind, pcm) in variants) {
                    val first = c.refs[0]
                    // (HFD_LOCATE_CACHED: only what was read already, to try the locator again at once.)
                    if (System.getenv("HFD_LOCATE_CACHED") != null && !File(dir, "readings/${c.id}-${kind.replace(' ', '-')}.tsv").exists()) continue
                    var listed: List<QuranSearch.Recited> = emptyList()
                    val (found, seconds, heard) = locate(readings(File(dir, "readings/${c.id}-${kind.replace(' ', '-')}.tsv"), pcm) { recognizer }, search) { listed = it }
                    // Right: the āya recited (from its first word, when it was heard from there); heard from
                    // inside it, that āya or the next (what was left of it may be too little to tell).
                    val right = found != null && found.ref.sura == first.sura && when (kind) {
                        "inside" -> found.ref.aya in first.aya..first.aya + 1
                        else -> found.ref.aya == first.aya && found.word <= 1
                    }
                    // Not found, but among the places listed (the text says it in several: one is chosen, or more is recited).
                    val among = found == null && listed.any { it.ref.sura == first.sura && it.ref.aya in first.aya..first.aya + 2 }
                    if (among) offered.merge(kind, 1, Int::plus)
                    totals.getOrPut(kind) { ArrayList() } += if (found == null) null else if (right) seconds else -1.0
                    if (!right) out.append(
                        "${c.id} ($kind, ${c.reciter.substringBefore('_')}): ${if (found == null) "not found" + (if (among) ", listed among ${listed.joinToString(" ") { it.ref.toString() }}" else "") else "WRONG ${found.ref} word ${found.word} (%.2f) after %.1f s".format(found.score, seconds)}; heard «$heard»\n",
                    )
                }
            }
        }
        var right = 0
        var wrong = 0
        var all = 0
        for ((kind, t) in totals) {
            val times = t.filterNotNull().filter { it >= 0 }.sorted()
            fun q(p: Double) = if (times.isEmpty()) Double.NaN else times[((times.size - 1) * p).toInt()]
            val w = t.count { it != null && it < 0 }
            out.append("$kind: right ${times.size}/${t.size} (%.1f %%), wrong $w, not found ${t.count { it == null }} (${offered[kind] ?: 0} of them listed to choose from); found after p50 %.1f s, p90 %.1f s, at most %.1f s\n".format(100.0 * times.size / t.size, q(0.5), q(0.9), q(1.0)))
            right += times.size
            wrong += w
            all += t.size
        }
        out.append("ALL: right $right/$all (%.1f %%), wrong $wrong, not found ${all - right - wrong}\n".format(100.0 * right / maxOf(1, all)))
        File(dir, System.getenv("HFD_LOCATE_OUT") ?: "locate.txt").writeText(out.toString())
    }

    /**
     * The same on the Recite bench's recordings (HFD_BENCH: tools/recite/prepare.py), which are a
     * few passages only, but as the owner's phone hears them: faint, over noise, muffled, in a
     * pocket, through earbuds' call audio, with restarts. Each is heard from its start (the
     * basmala first, before a sūra). Writes <HFD_BENCH>/locate.txt.
     */
    @Test
    fun phone() {
        val dir = System.getenv("HFD_BENCH")?.takeIf { it.isNotBlank() }
        val model = System.getenv("HFD_CTC")?.takeIf { it.isNotBlank() }
        Assume.assumeTrue("HFD_BENCH and HFD_CTC not set", dir != null && model != null && System.getenv("HFD_LOCATE") == null)
        val parts = Parts(HfdJson.decodeFromString(PartsFile.serializer(), Assets.file("quran/parts.json").readText()))
        val search = QuranSearch(SuraNames.apply(Assets.suras), Assets.quran, parts)
        search.prepare()
        val out = StringBuilder()
        val totals = LinkedHashMap<String, IntArray>() // right, wrong, not found
        val times = ArrayList<Double>()
        OrtCtcModel(model!!, 4).use { ort ->
            val recognizer by lazy { CtcRecognizer(ort) }
            for (line in File(dir, "cases.tsv").readLines().filter { it.isNotBlank() && !it.startsWith("#") }) {
                val f = line.split('\t')
                val id = f[0]
                val first = AyahRef.parse(f[3].substringBefore('@'))!!
                val begins = f[3].substringAfter('@').substringBefore('-').toDouble()
                val kind = listOf("noisy", "fast", "pauses", "loud", "muffled", "rumble", "pocket", "sco", "flow").first { "-$it" in id }
                val (found, seconds, heard) = locate(readings(File(dir, "readings-locate/$id.tsv"), app.hfd.core.recite.Wav.read(File(dir, f[2]))) { recognizer }, search)
                val right = found != null && found.ref == first && found.word <= 1
                val t = totals.getOrPut(kind) { IntArray(3) }
                t[if (right) 0 else if (found != null) 1 else 2]++
                if (right) times += seconds - begins
                if (!right) out.append("$id: ${if (found == null) "not found" else "WRONG ${found.ref} word ${found.word} (%.2f) after %.1f s".format(found.score, seconds)}; heard «$heard»\n")
            }
        }
        val all = IntArray(3)
        for ((kind, t) in totals) {
            out.append("$kind: right ${t[0]}/${t.sum()}, wrong ${t[1]}, not found ${t[2]}\n")
            for (i in 0..2) all[i] += t[i]
        }
        times.sort()
        fun q(p: Double) = if (times.isEmpty()) Double.NaN else times[((times.size - 1) * p).toInt()]
        out.append("ALL: right ${all[0]}/${all.sum()} (%.1f %%), wrong ${all[1]}, not found ${all[2]}; found p50 %.1f s, p90 %.1f s after the passage's first word begins\n".format(100.0 * all[0] / maxOf(1, all.sum()), q(0.5), q(0.9)))
        File(dir, "locate.txt").writeText(out.toString())
    }

    private class Reading(val seconds: Double, val final: Boolean, val text: String)

    /**
     * [pcm] as the app hears it: every reading of each utterance as it grows, and when. Kept in
     * <HFD_LOCATE>/readings (the recogniser is most of this bench's time; the locator can then be
     * tried again in seconds).
     */
    private fun readings(cache: File, pcm: FloatArray, recognizer: () -> CtcRecognizer): List<Reading> {
        if (cache.exists()) return cache.readLines().filter { it.isNotBlank() }.map { l -> l.split('\t').let { Reading(it[0].toDouble(), it[1] == "1", it.getOrElse(2) { "" }) } }
        val segmenter = Segmenter()
        val out = ArrayList<Reading>()
        var at = 0
        val limit = minOf(pcm.size, MAX_SECONDS * Segmenter.RATE)
        fun hear(us: List<app.hfd.core.recite.Utterance>) {
            for (u in us) out += Reading(at / Segmenter.RATE.toDouble(), u.final, recognizer().transcribe(Clarity.prepare(u.pcm)).replace('\t', ' ').replace('\n', ' '))
        }
        while (at + Segmenter.FRAME <= limit) {
            at += Segmenter.FRAME
            hear(segmenter.feed(pcm.copyOfRange(at - Segmenter.FRAME, at)))
        }
        segmenter.end()?.let { hear(listOf(it)) }
        cache.parentFile.mkdirs()
        cache.writeText(out.joinToString("\n") { "${it.seconds}\t${if (it.final) 1 else 0}\t${it.text}" })
        return out
    }

    /** The readings given to the locator until it settles: the place, the seconds said by then, what was read. */
    private fun locate(readings: List<Reading>, search: QuranSearch, listed: (List<QuranSearch.Recited>) -> Unit = {}): Triple<Locator.Found?, Double, String> {
        val locator = Locator(search)
        val said = ArrayList<String>()
        var heard = ""
        for (r in readings) {
            if (r.seconds > MAX_SECONDS) break
            heard = (said + r.text).joinToString(" | ")
            if (r.final) said += r.text
            locator.heard(r.text, r.final)?.let { return Triple(it, r.seconds, heard) }
        }
        listed(locator.candidates)
        return Triple(null, readings.lastOrNull()?.seconds ?: 0.0, heard)
    }

    private fun read(f: File): FloatArray {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(b.remaining()).also { b.get(it) }
    }

    /** One after the other, a breath between them. */
    private fun join(sounds: List<FloatArray>): FloatArray {
        val gap = FloatArray(Segmenter.RATE * 4 / 10)
        val out = ArrayList<FloatArray>()
        for ((i, s) in sounds.withIndex()) {
            if (i > 0) out += gap
            out += s
        }
        return FloatArray(out.sumOf { it.size }).also { all ->
            var at = 0
            for (s in out) {
                s.copyInto(all, at)
                at += s.size
            }
        }
    }

    private companion object {
        const val MAX_SECONDS = 24
    }
}
