package app.hfd.core.quran

import app.hfd.core.Assets
import app.hfd.core.HfdJson
import app.hfd.core.ctc.CtcRecognizer
import app.hfd.core.ctc.OrtCtcModel
import app.hfd.core.recite.Clarity
import app.hfd.core.recite.Wav
import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * The search by reciting on the Recite bench's recordings (HFD_BENCH: tools/recite/prepare.py)
 * with the CTC recogniser (HFD_CTC: its ONNX file): six seconds taken at several places of each
 * recording (cut wherever they fall, often inside a word), read freely, looked for in the whole
 * Qur'an. Right when a place found is an āya being recited in those seconds: the first place, or
 * one of those listed (a phrase the text says in several places has them all). Prints the share by kind of recording to report.txt's
 * neighbour, search.txt. Not run without those two variables.
 */
class RecitedSearchBench {
    @Test
    fun bench() {
        val dir = System.getenv("HFD_BENCH")
        val model = System.getenv("HFD_CTC")?.takeIf { it.isNotBlank() }
        Assume.assumeTrue("HFD_BENCH and HFD_CTC not set", dir != null && model != null)
        val parts = Parts(HfdJson.decodeFromString(PartsFile.serializer(), Assets.file("quran/parts.json").readText()))
        val search = QuranSearch(SuraNames.apply(Assets.suras), Assets.quran, parts)
        val seconds = System.getenv("HFD_SEARCH_SECONDS")?.toDoubleOrNull() ?: 6.0
        val out = StringBuilder()
        val byKind = LinkedHashMap<String, IntArray>() // first place right, right among those listed, found something, tried
        OrtCtcModel(model!!, 4).use { ort ->
            val recognizer = CtcRecognizer(ort)
            for (line in File(dir, "cases.tsv").readLines().filter { it.isNotBlank() && !it.startsWith("#") }) {
                val f = line.split('\t')
                val id = f[0]
                // "2:1@5.52-13.96~…": each āya's span in the recording.
                val spans = f[3].split(',').map { s ->
                    val (ref, rest) = s.split('@')
                    val (a, b) = rest.substringBefore('~').split('-').map(String::toDouble)
                    Triple(AyahRef.parse(ref)!!, a, b)
                }
                val pcm = Wav.read(File(dir, f[2]))
                val kind = listOf("noisy", "fast", "pauses", "loud", "muffled", "rumble", "pocket", "sco", "flow").first { "-$it" in id }
                val total = byKind.getOrPut(kind) { IntArray(4) }
                val from = spans.first().second
                val to = spans.last().third - seconds
                var at = from
                while (at <= to) {
                    val clip = pcm.copyOfRange((at * 16000).toInt(), ((at + seconds) * 16000).toInt().coerceAtMost(pcm.size))
                    val heard = recognizer.transcribe(Clarity.prepare(clip))
                    val hits = search.recited(heard)
                    // The āyāt said in the clip for a second at least.
                    val said = spans.filter { minOf(it.third, at + seconds) - maxOf(it.second, at) >= 1.0 }.map { it.first }.toSet()
                    fun QuranSearch.Recited.isSaid() = said.any { it >= ref && it <= last }
                    val right = hits.firstOrNull()?.isSaid() == true
                    val listed = hits.any { it.isSaid() }
                    total[3]++
                    if (hits.isNotEmpty()) total[2]++
                    if (listed) total[1]++
                    if (right) total[0]++
                    if (!listed) out.append("$id at %.1f s: said ${said.joinToString(",")} heard «$heard» found ${hits.take(3).joinToString(" ") { "${it.ref}(%.2f)".format(it.score) }}\n".format(at))
                    at += 7.0
                }
            }
        }
        val all = IntArray(4)
        for ((kind, t) in byKind) {
            out.append("$kind: first place right ${t[0]}/${t[3]} (%.0f %%), among those listed ${t[1]} (%.0f %%), something found ${t[2]}\n".format(100.0 * t[0] / maxOf(1, t[3]), 100.0 * t[1] / maxOf(1, t[3])))
            for (i in 0..3) all[i] += t[i]
        }
        out.append("ALL: first place right ${all[0]}/${all[3]} (%.1f %%), among those listed ${all[1]} (%.1f %%), something found ${all[2]}; clips of $seconds s\n".format(100.0 * all[0] / maxOf(1, all[3]), 100.0 * all[1] / maxOf(1, all[3])))
        File(dir, "search.txt").writeText(out.toString())
    }
}
