package app.hfd.core.recite

import kotlin.math.sqrt

/** Speech for the recogniser: utterance [id] so far ([final] false), or whole ([final] true). */
class Utterance(val id: Int, val pcm: FloatArray, val final: Boolean) {
    val seconds: Float get() = pcm.size / Segmenter.RATE.toFloat()
}

/**
 * Cuts the microphone's 20 ms frames (16 kHz mono, −1…1) into utterances for the recogniser.
 * An utterance starts when the voice rises above the room's noise (with a little audio before
 * it) and ends at a pause. While it goes on, the audio so far is handed over again every
 * [PARTIAL_FRAMES], so the text follows the reciter without waiting for a pause: people
 * reciting from memory hardly pause for long. A long utterance ends at a short pause after
 * 10 s, or at its quietest moment of the last two seconds at [MAX_FRAMES] (the model reads 30 s
 * windows and slips beyond ~25 s).
 */
class Segmenter {
    /**
     * The room's noise level (RMS): the quietest moments of the last three seconds. (Speech
     * always has some, between its sounds; a level creeping up during speech, as before, took
     * the soft sounds of a slow recitation — a ghunna, a madd — for silence and cut words.)
     */
    var noise = 0.01f
        private set
    private val recent = FloatArray(NOISE_FRAMES)
    private var recentCount = 0
    private val sorted = FloatArray(NOISE_FRAMES)
    /** The last frame's RMS. */
    var rms = 0f
        private set
    var voiced = false
        private set
    var inSpeech = false
        private set

    private val pre = ArrayDeque<FloatArray>()
    private val chunk = ArrayList<FloatArray>()
    private val chunkRms = ArrayList<Float>()
    private var silentFrames = 0
    private var voicedFrames = 0
    private var sizeAtPartial = 0
    private var voicedAtPartial = 0
    private var id = 0

    /** Takes the next frame; returns what is ready for the recogniser (usually nothing). */
    fun feed(frame: FloatArray): List<Utterance> {
        if (frame.isEmpty()) return emptyList()
        rms = rmsOf(frame)
        recent[recentCount % NOISE_FRAMES] = rms
        recentCount++
        val n0 = minOf(recentCount, NOISE_FRAMES)
        recent.copyInto(sorted, 0, 0, n0)
        sorted.sort(0, n0)
        noise = sorted[minOf(n0 - 1, NOISE_RANK)].coerceAtLeast(1e-5f)
        voiced = rms > maxOf(noise * VOICE_OVER_NOISE, MIN_RMS)
        if (!inSpeech) {
            pre.addLast(frame)
            if (pre.size > PRE_ROLL_FRAMES) pre.removeFirst()
            if (!voiced) return emptyList()
            inSpeech = true
            for (f in pre) add(f)
            pre.clear()
            silentFrames = 0
            voicedFrames = 1
            sizeAtPartial = 0
            voicedAtPartial = 0
            return emptyList()
        }
        add(frame)
        if (voiced) { silentFrames = 0; voicedFrames++ } else silentFrames++
        val n = chunk.size
        return when {
            silentFrames >= PAUSE_FRAMES -> listOfNotNull(finish())
            n >= SOFT_MAX_FRAMES && silentFrames >= SHORT_PAUSE_FRAMES -> listOfNotNull(finish())
            n >= MAX_FRAMES -> listOfNotNull(split())
            n - sizeAtPartial >= PARTIAL_FRAMES && voicedFrames > voicedAtPartial && voicedFrames >= MIN_VOICED_FRAMES -> {
                sizeAtPartial = n
                voicedAtPartial = voicedFrames
                listOf(Utterance(id, join(chunk), final = false))
            }
            else -> emptyList()
        }
    }

    /** Stopped: what was being said, whole. */
    fun end(): Utterance? = if (inSpeech) finish() else null

    /**
     * Drops the utterance being heard and starts a new one from here (after a hint: what was
     * recited before it has been followed already).
     */
    fun restart() {
        if (!inSpeech) return
        if (sizeAtPartial > 0) id++
        chunk.clear()
        chunkRms.clear()
        silentFrames = 0
        voicedFrames = 0
        sizeAtPartial = 0
        voicedAtPartial = 0
    }

    private fun add(frame: FloatArray) {
        chunk += frame
        chunkRms += rmsOf(frame)
    }

    private fun finish(): Utterance? {
        val u = if (voicedFrames >= MIN_VOICED_FRAMES) Utterance(id++, join(chunk), final = true) else null
        chunk.clear()
        chunkRms.clear()
        inSpeech = false
        silentFrames = 0
        voicedFrames = 0
        sizeAtPartial = 0
        voicedAtPartial = 0
        return u
    }

    /** At [MAX_FRAMES]: the utterance up to its quietest moment of the last two seconds; the rest goes on. */
    private fun split(): Utterance {
        val from = chunk.size - SPLIT_SEARCH_FRAMES
        var q = from
        for (i in from until chunk.size) if (chunkRms[i] < chunkRms[q]) q = i
        val u = Utterance(id++, join(chunk.subList(0, q)), final = true)
        val rest = chunk.subList(q, chunk.size).toList()
        val restRms = chunkRms.subList(q, chunkRms.size).toList()
        chunk.clear(); chunk += rest
        chunkRms.clear(); chunkRms += restRms
        voicedFrames = rest.size
        silentFrames = 0
        sizeAtPartial = 0
        voicedAtPartial = 0
        return u
    }

    companion object {
        const val RATE = 16_000
        /** 20 ms. */
        const val FRAME = 320
        private const val PRE_ROLL_FRAMES = 15 // 300 ms kept before the voice starts
        // 400 ms of silence ends an utterance: short ones, an āya or less, are read best (on a
        // longer stretch the model sometimes drops words it had heard).
        private const val PAUSE_FRAMES = 20
        private const val SHORT_PAUSE_FRAMES = 10 // 200 ms, enough to end a long one
        private const val MIN_VOICED_FRAMES = 10 // at least 200 ms of voice
        /** The audio so far, handed over again after this much more (1 s). */
        const val PARTIAL_FRAMES = 50
        private const val SOFT_MAX_FRAMES = 500 // 10 s
        const val MAX_FRAMES = 1000 // 20 s
        private const val SPLIT_SEARCH_FRAMES = 100
        /** The noise level: the 5th quietest frame of the last 3 s. */
        private const val NOISE_FRAMES = 150
        private const val NOISE_RANK = 4
        /** Voice: this much over the room's noise (×2.5 ≈ 8 dB), and above MIN_RMS (≈ −54 dB). */
        private const val VOICE_OVER_NOISE = 2.5f
        private const val MIN_RMS = 0.002f

        fun rmsOf(frame: FloatArray): Float {
            var sum = 0.0
            for (x in frame) sum += x * x
            return sqrt(sum / frame.size).toFloat()
        }

        private fun join(frames: List<FloatArray>): FloatArray {
            val out = FloatArray(frames.sumOf { it.size })
            var at = 0
            for (f in frames) { f.copyInto(out, at); at += f.size }
            return out
        }
    }
}
