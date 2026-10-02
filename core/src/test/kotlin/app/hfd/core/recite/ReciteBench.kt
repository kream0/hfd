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
 * model) and takes the phone's recognition time (0.57 s + 0.018 s per second of audio: 1.25 s +
 * 0.04 s in the diagnostics with the plain ARMv8 build, ×0.46 with ARMv8.2's dot products); the
 * text is followed by [Follower]. A word heard right once stays right, as on the phone's screen. As in the app, the recogniser is steered toward
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
        report.append('\n').append(totals.line()).append('\n').append(totals.places()).append('\n')
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
    /** When each word of an āya starts in the recording (the reciter's word timings, prepare.py), if known. */
    val wordStarts: Map<AyahRef, List<Double>> = emptyMap(),
) {
    /** One of the owner's own sessions: when each word was said isn't known (tools/recite/owner.py). */
    val owner: Boolean get() = spans.isEmpty()

    companion object {
        /** `id  2:1-5  file.wav  2:1@0.52-2.10~0.52;0.98,2:2@2.40-8.01,…  [3:1,3:3#1-8 (owner: words recited)]` */
        fun parse(line: String): Case {
            val p = line.split('\t')
            val (s, range) = p[1].split(':')
            val (a0, a1) = if ('-' in range) range.split('-').map(String::toInt) else listOf(range.toInt(), range.toInt())
            val refs = (a0..a1).map { AyahRef(s.toInt(), it) }
            val spans = LinkedHashMap<AyahRef, Pair<Double, Double>>()
            val wordStarts = HashMap<AyahRef, List<Double>>()
            for (item in p.getOrElse(3) { "" }.split(',').filter { it.isNotBlank() }) {
                val (ref, times) = item.split('@')
                val (rs, ra) = ref.split(':').map(String::toInt)
                val (t0, t1) = times.substringBefore('~').split('-').map(String::toDouble)
                if (spans.putIfAbsent(AyahRef(rs, ra), t0 to t1) == null && '~' in times) {
                    wordStarts[AyahRef(rs, ra)] = times.substringAfter('~').split(';').map(String::toDouble)
                }
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
            return Case(p[0], refs, p[2], spans, recited, from, wordStarts)
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
    /** Whole utterances after which the app pointed out a muffled microphone ([MuffledWarning]). */
    val warned: Int = 0,
    /** Each lag's word: its āya's first (0), a middle one (1) or the last (2); and whether its end is timed (else guessed). */
    val places: List<Int> = emptyList(),
    val timed: Int = 0,
) {
    private val warning get() = if (warned > 0) "  MUFFLED WARNING $warned/$finals" else ""

    fun line(): String {
        if (id.startsWith("owner-")) {
            return String.format(
                Locale.US, "%-34s OWNER'S SESSION: ok %d/%d words recited (wrong %d, missed %d, pending %d)  before them %d missed/%d  after them marked %d/%d  flips %d  calls %d+%d  %d ms/call%s",
                id, ok, said, wrong, missed, pending, skippedMissed, skipped, unreachedMarked, unreached, flips, partials, finals, msPerCall, warning,
            )
        }
        val l = lags.sorted()
        fun q(f: Double) = if (l.isEmpty()) Double.NaN else l[((l.size - 1) * f).toInt()]
        val skip = (if (skipped > 0) "  skipped $skippedMissed/$skipped missed" else "") +
            (if (unreachedMarked > 0) "  UNREACHED MARKED $unreachedMarked/$unreached" else "")
        return String.format(
            Locale.US, "%-34s ok %3d/%-3d wrong %2d missed %2d pending %2d  lag p50 %4.1f p90 %4.1f s  flips %2d  calls %d+%d  %d ms/call%s%s",
            id, ok, said, wrong, missed, pending, q(0.5), q(0.9), flips, partials, finals, msPerCall, skip, warning,
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
    private val places = ArrayList<Int>()
    private var timed = 0
    private var cases = 0

    operator fun plusAssign(r: Result) {
        said += r.said; ok += r.ok; wrong += r.wrong; missed += r.missed; pending += r.pending
        lags += r.lags
        places += r.places
        timed += r.timed
        cases++
    }

    /** Where the lag lies: by the word's place in its āya (an utterance usually is one). */
    fun places(): String {
        fun of(place: Int): String {
            val l = lags.indices.filter { places.getOrNull(it) == place }.map { lags[it] }.sorted()
            if (l.isEmpty()) return "none"
            fun q(f: Double) = l[((l.size - 1) * f).toInt()]
            return String.format(Locale.US, "p50 %.1f p90 %.1f s, %d of %d over 1 s", q(0.5), q(0.9), l.count { it > 1.0 }, l.size)
        }
        return "LAG BY PLACE ($timed of ${lags.size} words timed): āya's first word ${of(0)}; middle ${of(1)}; last ${of(2)}"
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
    /** Words heard right in some reading: they stay so (ReciteSession.wasRight). */
    private val everOk = BooleanArray(n)
    private var last = Array(n) { WordStatus.PENDING }
    private var flips = 0
    private var partials = 0
    private var finals = 0
    private val muffledWarning = MuffledWarning()
    private var warned = 0
    private val queue = ArrayDeque<Utterance>()
    private var inFlight: Triple<Utterance, String, Double>? = null
    /** The last partial reading: utterance, samples, text. */
    private var lastRead: Triple<Int, Int, String>? = null
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
        val seg = Segmenter(
            System.getenv("HFD_PARTIAL_FRAMES")?.toIntOrNull() ?: Segmenter.PARTIAL_FRAMES,
            System.getenv("HFD_SETTLE_FRAMES")?.toIntOrNull() ?: Segmenter.SETTLE_FRAMES,
        )
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
                val moved = follower.heard(u.id, text, u.final)
                if (u.final && muffledWarning.heard(Clarity.isMuffled(u.pcm), u.seconds, moved)) warned++
                readings.append(String.format(Locale.US, "      %6.1f s  #%d %s %4.1f s: %s\n", at, u.id, if (u.final) "final  " else "partial", u.seconds, text))
                observe(at)
                inFlight = null
            }
        }
        if (inFlight == null) {
            val u = queue.removeFirstOrNull() ?: return
            if (u.final) finals++ else partials++
            // As the app: a whole utterance the last partial read held all of takes that reading.
            val read = lastRead?.takeIf { u.final && u.settled > 0 && it.first == u.id && it.second == u.settled }
            if (read != null) {
                inFlight = Triple(u, read.third, t)
                return
            }
            val started = System.nanoTime()
            val text = decoder.transcribe(Clarity.prepare(u.pcm), expected = if (steer) follower.expected(u.id) else "")
            decodeNanos += System.nanoTime() - started
            inFlight = Triple(u, text, t + (0.57 + 0.018 * u.seconds) * PHONE_FACTOR)
            if (!u.final) lastRead = Triple(u.id, u.pcm.size, text)
        }
    }

    private fun observe(t: Double) {
        val now = tracker.status
        for (w in 0 until n) if (now[w] == WordStatus.OK) everOk[w] = true
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
        val places = ArrayList<Int>()
        var timed = 0
        val detail = StringBuilder()
        for ((a, t) in targets.withIndex()) {
            val span = case.spans[t.ref]
            // Each word's end: the next one's start (the āya's end for the last), from the reciter's
            // word timings; without them, spread over the āya by its letters.
            val starts = case.wordStarts[t.ref]?.takeIf { it.size == t.words.size }
            val weights = t.words.map { Arabic.skeleton(it).length + 1.0 }
            val total = weights.sum()
            var acc = 0.0
            val marks = StringBuilder()
            for ((w, word) in t.words.withIndex()) {
                val st = if (everOk[flat + w]) WordStatus.OK else tracker.status[flat + w]
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
                        val end = when {
                            starts == null -> span.first + (span.second - span.first) * acc / total
                            w + 1 < starts.size -> starts[w + 1]
                            else -> span.second
                        }
                        if (!shownAt[flat + w].isNaN()) {
                            lags += shownAt[flat + w] - end
                            places += when (w) { t.words.lastIndex -> 2; 0 -> 0; else -> 1 }
                            if (starts != null) timed++
                        }
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
            warned,
            places,
            timed,
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
