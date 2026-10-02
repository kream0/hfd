package app.hfd.core.recite

import app.hfd.core.Assets
import app.hfd.core.quran.AyahRef
import org.junit.Assume
import org.junit.Test
import java.io.BufferedOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.random.Random

/**
 * The Recite pipeline on real recitations, as the phone runs it. Each case (tools/recite/prepare.py:
 * EveryAyah recordings of a passage, brought down to the level and noise of the owner's phone
 * microphone) is fed to [Segmenter] frame by frame on a simulated clock; each utterance goes
 * through [Level] to the app's decoding (tools/recite/decoder.c: the JNI's parameters, the same
 * model) and takes the phone's recognition time (1.25 s + 0.04 s per second of audio, from the
 * diagnostics); the text is followed by [Follower]. As in the app, the recogniser is steered toward
 * what the reciter is expected to say ([Follower.expected], app/src/main/cpp/bias.h). Reports how
 * many words end right and how long after being said each word shows. Runs only in
 * .github/workflows/recite.yml (HFD_BENCH set; HFD_BENCH_ONLY, a regular expression, picks cases).
 */
class ReciteBench {
    @Test
    fun bench() {
        val dir = System.getenv("HFD_BENCH")
        Assume.assumeTrue("HFD_BENCH not set", dir != null)
        val only = System.getenv("HFD_BENCH_ONLY")?.takeIf { it.isNotBlank() }?.let(::Regex)
        val cases = File(dir, "cases.tsv").readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map(Case::parse)
            .filter { only == null || only.containsMatchIn(it.id) }
        val report = StringBuilder()
        val totals = Totals()
        // These cases also run decoded freely (the app before 2 Oct), to compare: degraded audio,
        // the owner's sessions, going back / skipping, and clean al-Fātiḥa (steering mustn't hurt a
        // clear voice). (Measured and not kept: priming the recogniser with the text before, 30 Sept,
        // 80 % of these words right → 38 %; equalising each utterance to a speaking voice, 1 Oct.)
        val freeTotals = Totals()
        val steeredTotals = Totals()
        val owners = ArrayList<String>()
        Decoder(System.getenv("HFD_DECODER"), System.getenv("HFD_MODEL")).use { decoder ->
            for (c in cases) {
                val pcm = Wav.read(File(dir, c.wav))
                val r = Simulation(c, pcm, decoder).run()
                if (!c.owner) totals += r
                report.append(r.line()).append('\n')
                if (r.detail.isNotEmpty()) report.append(r.detail)
                println(r.line())
                if (c.owner || COMPARED.any { it in c.id } || c.id.startsWith("fatiha-") && c.id.endsWith("-flow")) {
                    val p = Simulation(c, pcm, decoder, steer = false).run()
                    if (!c.owner) { steeredTotals += r; freeTotals += p }
                    val line = "free   " + p.line()
                    report.append(line).append('\n')
                    if (p.detail.isNotEmpty()) report.append(p.detail)
                    println(line)
                    if (c.owner) { owners += r.line(); owners += line }
                }
            }
        }
        report.append('\n').append(totals.line()).append('\n')
        report.append("SUBSET steered: ").append(steeredTotals.line()).append('\n')
        report.append("SUBSET free:    ").append(freeTotals.line()).append('\n')
        for (line in owners) report.append(line).append('\n')
        File(dir, "report.txt").writeText(report.toString())
        println(totals.line())
    }

    companion object {
        private val COMPARED = listOf("muffled", "pocket", "noisy", "sco", "-skip", "-again")
    }
}

/**
 * A recording of [refs] (the passage), with where each āya recited lies in it (the first time,
 * when the reciter went back and said it again).
 */
class Case(
    val id: String, val refs: List<AyahRef>, val wav: String, val spans: Map<AyahRef, Pair<Double, Double>>,
    /** The owner's sessions: the words recited in them, per āya (from listening to them, tools/recite/owner.tsv), if known. */
    val recited: Map<AyahRef, IntRange>? = null,
    /** Where the app stood when the session began, āya and word (what is before was recited already). */
    val from: Pair<AyahRef, Int>? = null,
) {
    /** One of the owner's own sessions: when each word was said isn't known (tools/recite/owner.py). */
    val owner: Boolean get() = spans.isEmpty()

    companion object {
        /** `id  2:1-5  file.wav  2:1@0.52-2.10,2:2@2.40-8.01,…  [3:1,3:3#1-8 (owner: words recited)]` */
        fun parse(line: String): Case {
            val p = line.split('\t')
            val (s, range) = p[1].split(':')
            val (a0, a1) = if ('-' in range) range.split('-').map(String::toInt) else listOf(range.toInt(), range.toInt())
            val refs = (a0..a1).map { AyahRef(s.toInt(), it) }
            val spans = LinkedHashMap<AyahRef, Pair<Double, Double>>()
            for (item in p.getOrElse(3) { "" }.split(',').filter { it.isNotBlank() }) {
                val (ref, times) = item.split('@')
                val (rs, ra) = ref.split(':').map(String::toInt)
                val (t0, t1) = times.split('-').map(String::toDouble)
                spans.putIfAbsent(AyahRef(rs, ra), t0 to t1)
            }
            val recited = p.getOrNull(4)?.takeIf { it.isNotBlank() }?.split(',')?.associate { item ->
                val (ref, words) = if ('#' in item) item.split('#').let { it[0] to it[1] } else item to null
                val (rs, ra) = ref.split(':').map(String::toInt)
                val range = words?.split('-')?.map(String::toInt)?.let { (w0, w1) -> (w0 - 1)..(w1 - 1) } ?: 0..Int.MAX_VALUE
                AyahRef(rs, ra) to range
            }
            val from = p.getOrNull(5)?.takeIf { it.isNotBlank() }?.let { item ->
                val (ref, word) = if ('#' in item) item.split('#').let { it[0] to it[1].toInt() - 1 } else item to 0
                val (s, a) = ref.split(':').map(String::toInt)
                AyahRef(s, a) to word
            }
            return Case(p[0], refs, p[2], spans, recited, from)
        }
    }
}

class Result(
    val id: String,
    /** Words recited (their āya is in the audio), and how they ended. */
    val said: Int, val ok: Int, val wrong: Int, val missed: Int, val pending: Int,
    /** Words of āyāt left out of the audio, and how many of them ended MISSED. */
    val skipped: Int, val skippedMissed: Int,
    /** Words after the last āya recited, and how many of them were marked all the same. */
    val unreached: Int, val unreachedMarked: Int,
    /** Seconds from the end of each word said to its first showing. */
    val lags: List<Double>,
    val flips: Int, val partials: Int, val finals: Int,
    val detail: String,
    /** Time the decoder took per call here (the phone's is about 1.25 s + 0.04 s per second of audio, greedy). */
    val msPerCall: Long = 0,
) {
    fun line(): String {
        if (id.startsWith("owner-")) {
            return String.format(
                Locale.US, "%-34s OWNER'S SESSION: ok %d/%d words recited (wrong %d, missed %d, pending %d)  before them %d missed/%d  after them marked %d/%d  flips %d  calls %d+%d  %d ms/call",
                id, ok, said, wrong, missed, pending, skippedMissed, skipped, unreachedMarked, unreached, flips, partials, finals, msPerCall,
            )
        }
        val l = lags.sorted()
        fun q(f: Double) = if (l.isEmpty()) Double.NaN else l[((l.size - 1) * f).toInt()]
        val skip = (if (skipped > 0) "  skipped $skippedMissed/$skipped missed" else "") +
            (if (unreachedMarked > 0) "  UNREACHED MARKED $unreachedMarked/$unreached" else "")
        return String.format(
            Locale.US, "%-34s ok %3d/%-3d wrong %2d missed %2d pending %2d  lag p50 %4.1f p90 %4.1f s  flips %2d  calls %d+%d  %d ms/call%s",
            id, ok, said, wrong, missed, pending, q(0.5), q(0.9), flips, partials, finals, msPerCall, skip,
        )
    }
}

class Totals {
    private var said = 0
    private var ok = 0
    private var wrong = 0
    private var missed = 0
    private var pending = 0
    private val lags = ArrayList<Double>()
    private var cases = 0

    operator fun plusAssign(r: Result) {
        said += r.said; ok += r.ok; wrong += r.wrong; missed += r.missed; pending += r.pending
        lags += r.lags
        cases++
    }

    fun line(): String {
        val l = lags.sorted()
        fun q(f: Double) = if (l.isEmpty()) Double.NaN else l[((l.size - 1) * f).toInt()]
        return String.format(
            Locale.US, "ALL %d cases: ok %d/%d (%.1f %%), wrong %d, missed %d, pending %d; lag p50 %.1f s, p90 %.1f s",
            cases, ok, said, 100.0 * ok / maxOf(1, said), wrong, missed, pending, q(0.5), q(0.9),
        )
    }
}

private class Simulation(val case: Case, val pcm: FloatArray, val decoder: Decoder, val steer: Boolean = true) {
    private val targets = case.refs.map { ReciteTarget(it, Arabic.words(Assets.quran.text(it).orEmpty())) }
    private val follower = Follower(targets)
    private val tracker = follower.tracker
    private val n = tracker.size
    private val shownAt = DoubleArray(n) { Double.NaN }
    private var last = Array(n) { WordStatus.PENDING }
    private var flips = 0
    private var partials = 0
    private var finals = 0
    private val queue = ArrayDeque<Utterance>()
    private var inFlight: Triple<Utterance, String, Double>? = null
    private var decodeNanos = 0L
    private val readings = StringBuilder()

    fun run(): Result {
        // Where the app stood when the owner's session began: the āyāt before it done.
        case.from?.let { (from, word) ->
            val at = targets.indexOfFirst { it.ref == from }
            if (at >= 0 && (at > 0 || word > 0)) {
                val position = tracker.startOf(at) + word
                tracker.reset(Tracker.Mark(position, Array(n) { if (it < position) WordStatus.OK else WordStatus.PENDING }))
                last = tracker.status.copyOf()
            }
        }
        val seg = Segmenter()
        val frame = Segmenter.FRAME
        var t = 0.0
        var i = 0
        while (i + frame <= pcm.size) {
            t = (i + frame) / RATE
            seg.feed(pcm.copyOfRange(i, i + frame)).forEach(::offer)
            step(t)
            i += frame
        }
        // The reciter stops, and presses stop a second later.
        val rnd = Random(1)
        repeat(50) {
            t += frame / RATE
            seg.feed(FloatArray(frame) { (rnd.nextFloat() - 0.5f) * 0.0005f }).forEach(::offer)
            step(t)
        }
        seg.end()?.let(::offer)
        while (inFlight != null || queue.isNotEmpty()) {
            t = maxOf(t, inFlight?.third ?: t)
            step(t)
        }
        return result()
    }

    /** A newer reading of the utterance waiting replaces it (the app's queue does the same). */
    private fun offer(u: Utterance) {
        if (queue.lastOrNull()?.let { !it.final && it.id == u.id } == true) queue.removeLast()
        queue.addLast(u)
    }

    private fun step(t: Double) {
        inFlight?.let { (u, text, at) ->
            if (t >= at) {
                follower.heard(u.id, text, u.final)
                readings.append(String.format(Locale.US, "      %6.1f s  #%d %s %4.1f s: %s\n", at, u.id, if (u.final) "final  " else "partial", u.seconds, text))
                observe(at)
                inFlight = null
            }
        }
        if (inFlight == null) {
            val u = queue.removeFirstOrNull() ?: return
            if (u.final) finals++ else partials++
            val started = System.nanoTime()
            val text = decoder.transcribe(Clarity.prepare(u.pcm), expected = if (steer) follower.expected(u.id) else "")
            decodeNanos += System.nanoTime() - started
            inFlight = Triple(u, text, t + (1.25 + 0.04 * u.seconds) * PHONE_FACTOR)
        }
    }

    private fun observe(t: Double) {
        val now = tracker.status
        for (w in 0 until n) {
            if (now[w] != WordStatus.PENDING && shownAt[w].isNaN()) shownAt[w] = t
            else if (!shownAt[w].isNaN() && now[w] != last[w]) flips++
        }
        last = now.copyOf()
    }

    private fun result(): Result {
        var flat = 0
        var said = 0; var ok = 0; var wrong = 0; var missed = 0; var pending = 0
        var skipped = 0; var skippedMissed = 0
        var unreached = 0; var unreachedMarked = 0
        // The owner's sessions: the words recited in them (all, if not known).
        val lastRecited = if (case.owner) targets.lastIndex else targets.indexOfLast { it.ref in case.spans }
        val recitedFlat = BooleanArray(n).also { r ->
            var at = 0
            for (t in targets) {
                for (w in t.words.indices) r[at + w] = case.owner && (case.recited?.get(t.ref)?.contains(w) ?: (case.recited == null))
                at += t.words.size
            }
        }
        val lastSaid = recitedFlat.lastIndexOf(true)
        val lags = ArrayList<Double>()
        val detail = StringBuilder()
        for ((a, t) in targets.withIndex()) {
            val span = case.spans[t.ref]
            // Each word's end, spread over the āya by its letters.
            val weights = t.words.map { Arabic.skeleton(it).length + 1.0 }
            val total = weights.sum()
            var acc = 0.0
            val marks = StringBuilder()
            for ((w, word) in t.words.withIndex()) {
                val st = tracker.status[flat + w]
                acc += weights[w]
                if (case.owner && !recitedFlat[flat + w]) {
                    if (flat + w > lastSaid) {
                        unreached++
                        if (st != WordStatus.PENDING) unreachedMarked++
                    } else {
                        skipped++
                        if (st == WordStatus.MISSED) skippedMissed++
                    }
                } else if (span != null || case.owner) {
                    said++
                    when (st) {
                        WordStatus.OK -> ok++
                        WordStatus.WRONG -> wrong++
                        WordStatus.MISSED -> missed++
                        else -> pending++
                    }
                    if (span != null) {
                        val end = span.first + (span.second - span.first) * acc / total
                        if (!shownAt[flat + w].isNaN()) lags += shownAt[flat + w] - end
                    }
                } else if (a > lastRecited) {
                    unreached++
                    if (st != WordStatus.PENDING) unreachedMarked++
                } else {
                    skipped++
                    if (st == WordStatus.MISSED) skippedMissed++
                }
                marks.append(
                    when (st) {
                        WordStatus.OK -> "✓"
                        WordStatus.WRONG -> "✗"
                        WordStatus.MISSED -> "–"
                        WordStatus.HINTED -> "?"
                        WordStatus.PENDING -> "·"
                    },
                )
                if ((span != null || recitedFlat[flat + w]) && st != WordStatus.OK) marks.append("(").append(word).append(")")
                marks.append(' ')
            }
            flat += t.words.size
            detail.append("    ${t.ref}${if (span == null && !case.owner) (if (a > lastRecited) " (not reached)" else " (not recited)") else ""}: $marks\n")
        }
        val bad = said - ok + (skipped - skippedMissed) + unreachedMarked
        return Result(
            case.id, said, ok, wrong, missed, pending, skipped, skippedMissed, unreached, unreachedMarked, lags, flips, partials, finals,
            if (bad > 0 || case.owner) detail.toString() + readings else "",
            decodeNanos / 1_000_000 / maxOf(1, partials + finals),
        )
    }

    companion object {
        const val RATE = Segmenter.RATE.toDouble()
        /** The phone's time per reading relative to the app's model (a bigger model: HFD_PHONE_FACTOR). */
        val PHONE_FACTOR = System.getenv("HFD_PHONE_FACTOR")?.toDoubleOrNull() ?: 1.0
    }
}

/**
 * The app's decoding, as a process: 4-byte little-endian sample count, float32 samples, 4-byte
 * prompt length and its UTF-8 bytes, 4-byte length and UTF-8 bytes of the expected text → a line of text.
 */
private class Decoder(exe: String, model: String) : AutoCloseable {
    private val process = ProcessBuilder(exe, model).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    private val out = BufferedOutputStream(process.outputStream)
    private val input = process.inputStream.bufferedReader(Charsets.UTF_8)

    fun transcribe(pcm: FloatArray, prompt: String = "", expected: String = ""): String {
        val text = prompt.toByteArray(Charsets.UTF_8)
        val exp = expected.toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(4 + 4 * pcm.size + 4 + text.size + 4 + exp.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(pcm.size)
        for (x in pcm) buf.putFloat(x)
        buf.putInt(text.size)
        buf.put(text)
        buf.putInt(exp.size)
        buf.put(exp)
        out.write(buf.array())
        out.flush()
        return input.readLine()?.trim() ?: error("decoder stopped")
    }

    override fun close() {
        runCatching { out.close() }
        process.waitFor()
    }
}

/** 16-bit PCM WAV, mono 16 kHz. */
object Wav {
    fun read(file: File): FloatArray {
        val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        require(String(b.array(), 0, 4) == "RIFF" && String(b.array(), 8, 4) == "WAVE") { "$file is not a WAV file" }
        var at = 12
        while (at + 8 <= b.limit()) {
            val id = String(b.array(), at, 4)
            val size = b.getInt(at + 4)
            if (id == "fmt ") {
                require(b.getShort(at + 8 + 2).toInt() == 1 && b.getInt(at + 8 + 4) == Segmenter.RATE && b.getShort(at + 8 + 14).toInt() == 16) {
                    "$file: want 16-bit mono 16 kHz"
                }
            }
            if (id == "data") {
                val n = minOf(size, b.limit() - at - 8) / 2
                return FloatArray(n) { b.getShort(at + 8 + 2 * it) / 32768f }
            }
            at += 8 + size + (size and 1)
        }
        error("$file has no data")
    }
}
