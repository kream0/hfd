package app.hfd.core.tajweed

import kotlin.math.sqrt

/**
 * Whether a recitation holds its long vowels as the rules ask, read from where the recogniser
 * places the text's letters in time (a frame is 40 ms).
 *
 * What is measured is the beat ([Beat]): from a consonant to the next. The yardstick is the
 * recitation's own: its usual natural madd (a long vowel of two ḥarakāt, of which any passage has
 * plenty). A madd of four or five (before a hamza) or of six (before a sukūn) that lasts no longer
 * than about a natural one was not held.
 *
 * Measured on 137 āyāt as four masters recite them, from slow to fast (TajweedCheckBench, with
 * this code; tools/tajweed/timing.py has the durations): a madd before a hamza lasts 3 to 5 times
 * their natural one, never under twice; none of their 213 is flagged, nor a madd of six. Each cut
 * down to the reciter's natural madd is caught 50 times in 50 at a slow pace (al-Ḥuṣarī, a natural
 * madd of 0.56 s), 49 in 53 and 46 in 54 at a measured one (0.32 to 0.36 s), 25 in 54 at a fast one
 * (al-Muʿayqilī, 0.28 s: seven frames, and the cut vowel still measures 1.5 to 1.9 of them with
 * the hamza's closure). The check flags under [LONG_MIN] natural ones, so that a reciter who
 * gives four ḥarakāt exactly (twice the vowel, about 1.7 times the beat) is never corrected.
 *
 * Left alone, because the timing cannot judge it letter by letter: a natural madd held too long
 * (tried: 2 % of the masters' were flagged, where they stop inside an āya and their mosque's echo
 * hides the pause), the ghunna (held, it lasts about twice a plain doubled letter on average, but
 * at a fast pace the two differ by a frame or two: one in eight of a master's was flagged), the
 * points of articulation, the heavy and light letters.
 */
object TajweedCheck {
    /** Seconds a frame of the recogniser lasts. */
    const val FRAME = 0.04f

    /** A madd of four or five is flagged under this many natural ones. */
    const val LONG_MIN = 1.45f

    /** A madd of six, under this many. */
    const val LAZIM_MIN = 1.9f

    /** The natural madds a recitation must have for its usual one to be known. */
    const val MIN_NATURAL = 3

    /** A beat as the recitation said it: [seconds] of voice (a breath taken inside it is not counted, but noted: [paused]). */
    class Timed(val beat: Beat, val seconds: Float, val paused: Boolean, val last: Boolean)

    enum class Fault { TOO_SHORT }

    /**
     * The long vowel of the beat at [at] (its consonant's place in the text measured) was held
     * [seconds], where the recitation's natural madd lasts [natural].
     */
    data class Finding(val at: Int, val word: Int, val rule: Rule, val fault: Fault, val seconds: Float, val natural: Float)

    /**
     * The frames of [pcm] (16 kHz) that are a pause: quiet (under 6 % of the loud frames' level)
     * for 0.2 s or more. The breath between two phrases is not part of what is held.
     */
    fun pauses(pcm: FloatArray, frames: Int): BooleanArray {
        val energy = FloatArray(frames) { t ->
            val from = t * SAMPLES
            if (from + SAMPLES > pcm.size) 0f else {
                var sum = 0.0
                for (i in from until from + SAMPLES) sum += pcm[i] * pcm[i]
                sqrt(sum / SAMPLES).toFloat()
            }
        }
        if (frames == 0) return BooleanArray(0)
        val loud = energy.sortedArray()[((frames - 1) * 0.95f).toInt()]
        val quiet = BooleanArray(frames) { energy[it] < QUIET * loud }
        val pause = BooleanArray(frames)
        var i = 0
        while (i < frames) {
            if (!quiet[i]) { i++; continue }
            var j = i
            while (j < frames && quiet[j]) j++
            if (j - i >= PAUSE_FRAMES) for (k in i until j) pause[k] = true
            i = j
        }
        return pause
    }

    /**
     * [beats] timed: [placed] gives the frame of each character of the text the beats are of
     * ([Beat.at]); [pause] the frames that are a pause. The last beat runs to the end of the voice.
     * Beats the recogniser could not place are left out.
     */
    fun time(beats: List<Beat>, placed: IntArray, pause: BooleanArray): List<Timed> {
        val frames = pause.size
        var end = frames
        while (end > 0 && pause[end - 1]) end--
        val out = ArrayList<Timed>()
        for ((k, b) in beats.withIndex()) {
            val from = placed.getOrElse(b.at) { -1 }
            val to = if (k + 1 < beats.size) placed.getOrElse(beats[k + 1].at) { -1 } else end
            if (from < 0 || to <= from || to > frames) continue
            var voiced = 0
            for (t in from until to) if (!pause[t]) voiced++
            out += Timed(b, voiced * FRAME, paused = voiced < to - from, last = k == beats.lastIndex || b.last)
        }
        return out
    }

    /** The recitation's usual natural madd, in seconds; null when it has too few to tell. */
    fun natural(timed: List<Timed>): Float? {
        val plain = timed.filter { it.beat.madd == Rule.MADD_NATURAL && !it.paused && !it.last && it.beat.nasal == null && !it.beat.doubled }.map { it.seconds }.sorted()
        if (plain.size < MIN_NATURAL) return null
        return if (plain.size % 2 == 1) plain[plain.size / 2] else (plain[plain.size / 2 - 1] + plain[plain.size / 2]) / 2
    }

    /**
     * The long vowels of [timed] that were not held as their rule asks, against [natural] (the
     * recitation's usual natural madd; by default [timed]'s own).
     */
    fun check(timed: List<Timed>, natural: Float? = natural(timed)): List<Finding> {
        val n = natural ?: return emptyList()
        val out = ArrayList<Finding>()
        for (t in timed) {
            val rule = t.beat.madd ?: continue
            // The reciter stops on this word: a long vowel before the next word's hamza is then a plain one.
            val stopped = t.paused || t.last
            val fault = when (rule) {
                Rule.MADD_MUTTASIL -> if (t.seconds < LONG_MIN * n) Fault.TOO_SHORT else null
                Rule.MADD_MUNFASIL -> if (!stopped && t.seconds < LONG_MIN * n) Fault.TOO_SHORT else null
                Rule.MADD_LAZIM -> if (t.seconds < LAZIM_MIN * n) Fault.TOO_SHORT else null
                else -> null
            } ?: continue
            out += Finding(t.beat.at, t.beat.word, rule, fault, t.seconds, n)
        }
        return out
    }

    /**
     * The beats of a stretch of a recitation: words [from] until [to] of the āyāt's words laid end
     * to end ([ayat]: each āya's words), as said after [opening] (an isti'ādha, a basmala). Each
     * beat's [Beat.at] is its place in the text said ([opening] then the words, a space between),
     * and its [Beat.word] counts from the first āya's first word. The rules are each āya's whole
     * (a word in the middle of an āya is not at a stop).
     */
    fun stretch(ayat: List<List<String>>, from: Int, to: Int, opening: List<String> = emptyList()): List<Beat> {
        val out = ArrayList<Beat>()
        // Where each word said begins in the text said.
        var at = opening.sumOf { it.length + 1 }
        var base = 0
        for (words in ayat) {
            if (base + words.size > from && base < to) {
                val text = words.joinToString(" ")
                val starts = IntArray(words.size)
                for (w in 1 until words.size) starts[w] = starts[w - 1] + words[w - 1].length + 1
                val said = IntArray(words.size) { -1 }
                for (w in words.indices) if (base + w in from until to) { said[w] = at; at += words[w].length + 1 }
                for (b in Tajweed.beats(text)) if (said[b.word] >= 0) out += b.copy(at = said[b.word] + b.at - starts[b.word], word = base + b.word)
            }
            base += words.size
        }
        return out
    }

    private const val SAMPLES = 640
    private const val QUIET = 0.06f
    private const val PAUSE_FRAMES = 5
}
