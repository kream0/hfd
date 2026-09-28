package app.hfd.core.recite

import kotlin.math.sqrt

/**
 * Speech brought to a normal level before recognition. Phones' speech-recognition microphone
 * is faint (−50 dBFS for a normal voice on a Nothing Phone (4a) Pro, from the diagnostics): the
 * model then only catches scraps of words.
 */
object Level {
    /** 20 ms at 16 kHz. */
    private const val FRAME = 320
    /** Loud speech frames brought to −20 dBFS. */
    const val TARGET_RMS = 0.1f
    /** At most +40 dB. */
    const val MAX_GAIN = 100f

    /**
     * [pcm] with its loud frames (90th percentile of the frames' RMS) at [TARGET_RMS], never
     * louder than full scale and never quieter than it was; and the gain applied.
     */
    fun normalize(pcm: FloatArray): Pair<FloatArray, Float> {
        if (pcm.isEmpty()) return pcm to 1f
        val frames = (pcm.size + FRAME - 1) / FRAME
        val rms = FloatArray(frames) { f ->
            var sum = 0.0
            val from = f * FRAME
            val to = minOf(pcm.size, from + FRAME)
            for (i in from until to) sum += pcm[i] * pcm[i]
            sqrt(sum / (to - from)).toFloat()
        }
        rms.sort()
        val loud = rms[((frames - 1) * 0.9).toInt()]
        var peak = 0f
        for (x in pcm) peak = maxOf(peak, kotlin.math.abs(x))
        if (loud <= 0f || peak <= 0f) return pcm to 1f
        val gain = minOf(TARGET_RMS / loud, MAX_GAIN, 0.98f / peak).coerceAtLeast(1f)
        return FloatArray(pcm.size) { pcm[it] * gain } to gain
    }
}
