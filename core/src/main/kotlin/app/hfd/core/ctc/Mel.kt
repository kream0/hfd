package app.hfd.core.ctc

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The spectrogram a NeMo speech model (FastConformer) listens to, as NeMo computes it: the sound
 * pre-emphasised (0.97), 25 ms Hann windows every 10 ms in a 512-point transform, [MELS] mel
 * bands ([filters]: Slaney's, as the model's own fb.bin), their log, then each band brought to
 * zero mean and unit deviation over the utterance.
 */
class Mel(private val filters: FloatArray = slaneyFilters()) {
    init {
        require(filters.size == MELS * BINS) { "mel filters: ${filters.size} values, not $MELS × $BINS" }
    }

    /** [data]: band by band, [frames] values each; the model is told [length] of them are sound. */
    class Features(val data: FloatArray, val frames: Int, val length: Int)

    // Each band's bins that count (a triangle: a handful of the 257).
    private val first = IntArray(MELS) { m -> (0 until BINS).firstOrNull { filters[m * BINS + it] != 0f } ?: 0 }
    private val last = IntArray(MELS) { m -> (BINS - 1 downTo 0).firstOrNull { filters[m * BINS + it] != 0f } ?: -1 }

    fun features(pcm: FloatArray): Features {
        val length = pcm.size / HOP
        val frames = length + 1
        val data = FloatArray(MELS * frames)
        val re = DoubleArray(FFT)
        val im = DoubleArray(FFT)
        val power = DoubleArray(BINS)
        for (f in 0 until frames) {
            // The window's first sample: the frame is centred on f × HOP, the window in the 512.
            val start = f * HOP - FFT / 2 + OFFSET
            for (i in 0 until FFT) { re[i] = 0.0; im[i] = 0.0 }
            for (i in 0 until WINDOW) {
                val at = start + i
                if (at < 0 || at >= pcm.size) continue
                val x = if (at == 0) pcm[0].toDouble() else pcm[at] - PREEMPHASIS * pcm[at - 1]
                re[OFFSET + i] = x * HANN[i]
            }
            fft(re, im)
            for (k in 0 until BINS) power[k] = re[k] * re[k] + im[k] * im[k]
            for (m in 0 until MELS) {
                var sum = 0.0
                val row = m * BINS
                for (k in first[m]..last[m]) sum += power[k] * filters[row + k]
                data[m * frames + f] = ln(sum + FLOOR).toFloat()
            }
        }
        for (m in 0 until MELS) {
            val row = m * frames
            var mean = 0.0
            for (f in 0 until length) mean += data[row + f]
            mean /= maxOf(1, length)
            var spread = 0.0
            for (f in 0 until length) { val d = data[row + f] - mean; spread += d * d }
            val deviation = sqrt(spread / maxOf(1, length - 1)) + 1e-5
            for (f in 0 until length) data[row + f] = ((data[row + f] - mean) / deviation).toFloat()
            for (f in length until frames) data[row + f] = 0f
        }
        return Features(data, frames, length)
    }

    companion object {
        const val MELS = 80
        const val FFT = 512
        const val BINS = FFT / 2 + 1
        /** 10 ms at 16 kHz. */
        const val HOP = 160
        /** 25 ms. */
        const val WINDOW = 400
        private const val OFFSET = (FFT - WINDOW) / 2
        private const val PREEMPHASIS = 0.97
        /** 2⁻²⁴, NeMo's guard under the log. */
        private const val FLOOR = 5.9604644775390625e-8
        private val HANN = DoubleArray(WINDOW) { 0.5 - 0.5 * cos(2 * PI * it / (WINDOW - 1)) }

        private const val RATE = 16000.0

        /**
         * The [MELS] triangular filters from 0 to 8 kHz on Slaney's mel scale (linear to 1 kHz,
         * logarithmic above), each of unit area: what librosa gives NeMo, [MELS] × [BINS].
         */
        fun slaneyFilters(): FloatArray {
            val linear = 200.0 / 3
            val step = ln(6.4) / 27
            fun mel(hz: Double) = if (hz >= 1000) 15 + ln(hz / 1000) / step else hz / linear
            fun hz(mel: Double) = if (mel >= 15) 1000 * kotlin.math.exp(step * (mel - 15)) else mel * linear
            val top = mel(RATE / 2)
            val edges = DoubleArray(MELS + 2) { hz(top * it / (MELS + 1)) }
            val out = FloatArray(MELS * BINS)
            for (m in 0 until MELS) {
                val area = 2 / (edges[m + 2] - edges[m])
                for (k in 0 until BINS) {
                    val f = k * RATE / FFT
                    val rise = (f - edges[m]) / (edges[m + 1] - edges[m])
                    val fall = (edges[m + 2] - f) / (edges[m + 2] - edges[m + 1])
                    out[m * BINS + k] = (maxOf(0.0, minOf(rise, fall)) * area).toFloat()
                }
            }
            return out
        }

        private val COS = DoubleArray(FFT / 2) { cos(2 * PI * it / FFT) }
        private val SIN = DoubleArray(FFT / 2) { sin(2 * PI * it / FFT) }
        private val REVERSED = IntArray(FFT) { Integer.reverse(it) ushr (32 - 9) }

        /** In place, [FFT] points (radix 2). */
        internal fun fft(re: DoubleArray, im: DoubleArray) {
            for (i in 0 until FFT) {
                val j = REVERSED[i]
                if (j > i) {
                    val r = re[i]; re[i] = re[j]; re[j] = r
                    val m = im[i]; im[i] = im[j]; im[j] = m
                }
            }
            var size = 2
            while (size <= FFT) {
                val half = size / 2
                val step = FFT / size
                var from = 0
                while (from < FFT) {
                    for (k in 0 until half) {
                        val c = COS[k * step]
                        val s = -SIN[k * step]
                        val a = from + k
                        val b = a + half
                        val tr = re[b] * c - im[b] * s
                        val ti = re[b] * s + im[b] * c
                        re[b] = re[a] - tr; im[b] = im[a] - ti
                        re[a] += tr; im[a] += ti
                    }
                    from += size
                }
                size *= 2
            }
        }
    }
}
