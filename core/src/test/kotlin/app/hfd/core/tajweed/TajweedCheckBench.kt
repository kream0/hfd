package app.hfd.core.tajweed

import app.hfd.core.Assets
import app.hfd.core.ctc.CtcRecognizer
import app.hfd.core.ctc.OrtCtcModel
import app.hfd.core.quran.AyahRef
import app.hfd.core.recite.Arabic
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Recite's check of the long vowels ([TajweedCheck]) with the app's own code, on recordings of
 * masters: every flag on a recitation as it is is a false one, and every long vowel cut down to
 * the reciter's natural madd should be flagged. Not a test: it prints the counts.
 *
 * HFD_TAJWEED_AUDIO: a directory of `<reciter>/<sssaaa>.f32` (16 kHz mono float32, an āya each:
 * tools/tajweed/fetch.sh), HFD_CTC: the model's ONNX file. HFD_TAJWEED_OUT: where the report goes.
 */
class TajweedCheckBench {
    private fun pcm(file: File): FloatArray {
        val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(b.remaining()).also { b.get(it) }
    }

    @Test
    fun masters() {
        val dir = System.getenv("HFD_TAJWEED_AUDIO")
        val model = System.getenv("HFD_CTC")
        assumeTrue(dir != null && model != null)
        val recognizer = CtcRecognizer(OrtCtcModel(model!!, 4))
        val out = StringBuilder()
        val checked = HashMap<Pair<String, String>, Int>()
        val flagged = HashMap<Pair<String, String>, Int>()
        val cut = HashMap<Pair<String, String>, Int>()
        val caught = HashMap<Pair<String, String>, Int>()

        fun timed(sound: FloatArray, text: String, beats: List<Beat>): List<TajweedCheck.Timed>? {
            val (frames, placed) = CtcRecognizer.times(recognizer.transcribe(sound, CtcRecognizer.TIMES + text)) ?: return null
            return TajweedCheck.time(beats, placed, TajweedCheck.pauses(sound, frames))
        }
        fun kind(rule: Rule) = if (rule == Rule.MADD_LAZIM) "madd of six too short" else "madd of four or five too short"

        for (reciter in File(dir!!).listFiles { f -> f.isDirectory }!!.sortedBy { it.name }) {
            for (file in reciter.listFiles { f -> f.extension == "f32" }!!.sortedBy { it.name }) {
                val ref = AyahRef(file.nameWithoutExtension.take(3).toInt(), file.nameWithoutExtension.drop(3).toInt())
                // The text as Recite holds it: the āya's words, without the signs of pause.
                val text = Arabic.words(Assets.quran.text(ref)!!).joinToString(" ")
                val beats = Tajweed.beats(text)
                if (beats.isEmpty()) continue
                val sound = pcm(file)
                val timed = timed(sound, text, beats) ?: continue
                val natural = TajweedCheck.natural(timed) ?: continue
                val findings = TajweedCheck.check(timed, natural)
                for (t in timed) {
                    val rule = t.beat.madd ?: continue
                    if (rule != Rule.MADD_MUNFASIL && rule != Rule.MADD_MUTTASIL && rule != Rule.MADD_LAZIM) continue
                    if (rule == Rule.MADD_MUNFASIL && (t.paused || t.last)) continue
                    checked.merge(reciter.name to kind(rule), 1, Int::plus)
                }
                for (f in findings) {
                    flagged.merge(reciter.name to kind(f.rule), 1, Int::plus)
                    out.append("FALSE ${reciter.name} $ref ${f.rule} ${f.fault} ${"%.2f".format(f.seconds)} s (natural ${"%.2f".format(f.natural)}) in ${text.split(' ')[f.word]}\n")
                }
                // Each madd of four or more cut down to about the reciter's natural one: the beat keeps its start
                // (a natural madd less 80 ms) and its last 80 ms, the middle goes (a 20 ms fade across the cut).
                for ((k, b) in beats.withIndex()) {
                    val rule = b.madd ?: continue
                    if (rule != Rule.MADD_MUNFASIL && rule != Rule.MADD_MUTTASIL && rule != Rule.MADD_LAZIM) continue
                    val t = timed.firstOrNull { it.beat === b } ?: continue
                    if (t.paused || t.last || t.seconds <= natural + 0.16f) continue
                    val (_, placed) = CtcRecognizer.times(recognizer.transcribe(sound, CtcRecognizer.TIMES + text))!!
                    val t0 = placed[b.at]
                    val t1 = placed[beats[k + 1].at]
                    val a = ((t0 * TajweedCheck.FRAME + maxOf(0.08f, natural - 0.08f)) * 16000).toInt()
                    val z = ((t1 * TajweedCheck.FRAME - 0.08f) * 16000).toInt()
                    if (z - a < 1600) continue
                    val fade = 320
                    val shorter = FloatArray(sound.size - (z - a))
                    System.arraycopy(sound, 0, shorter, 0, a)
                    System.arraycopy(sound, z, shorter, a, sound.size - z)
                    for (i in 0 until fade) shorter[a - fade + i] = sound[a - fade + i] * (1 - i / fade.toFloat()) + sound[z - fade + i] * (i / fade.toFloat())
                    val again = timed(shorter, text, beats) ?: continue
                    cut.merge(reciter.name to kind(rule), 1, Int::plus)
                    val found = TajweedCheck.check(again).any { it.at == b.at && it.fault == TajweedCheck.Fault.TOO_SHORT }
                    if (found) caught.merge(reciter.name to kind(rule), 1, Int::plus)
                    else out.append("MISSED ${reciter.name} $ref $rule cut to ${again.firstOrNull { it.beat === b }?.seconds} s (natural ${TajweedCheck.natural(again)}) in ${text.split(' ')[b.word]}\n")
                }
            }
        }
        val summary = StringBuilder("TAJWEED CHECK on masters: flagged / checked as recited (false alarms), then caught / cut\n")
        for (key in (checked.keys + cut.keys).sortedWith(compareBy({ it.second }, { it.first }))) {
            summary.append("  ${key.second.padEnd(32)} ${key.first.padEnd(28)} false ${flagged[key] ?: 0}/${checked[key] ?: 0}   caught ${caught[key] ?: 0}/${cut[key] ?: 0}\n")
        }
        for (what in checked.keys.map { it.second }.distinct().sorted()) {
            val f = flagged.filterKeys { it.second == what }.values.sum()
            val c = checked.filterKeys { it.second == what }.values.sum()
            val g = caught.filterKeys { it.second == what }.values.sum()
            val u = cut.filterKeys { it.second == what }.values.sum()
            summary.append("  ${what.padEnd(32)} ${"ALL".padEnd(28)} false $f/$c   caught $g/$u\n")
        }
        File(System.getenv("HFD_TAJWEED_OUT") ?: "build/tajweed-check.txt").apply { parentFile?.mkdirs() }.writeText(summary.toString() + "\n" + out)
        println(summary)
    }
}
