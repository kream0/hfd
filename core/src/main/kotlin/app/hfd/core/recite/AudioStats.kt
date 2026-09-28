package app.hfd.core.recite

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What a chunk of microphone audio (16 kHz mono) sounds like, in numbers, for the diagnostics:
 * a phone's microphone processing can muffle, distort or cut speech in ways the recogniser
 * doesn't cope with, and the audio itself is never sent.
 */
data class AudioStats(
    val seconds: Float,
    val peakDb: Float,
    /** Loud frames (90th percentile of 20 ms frames). */
    val loudDb: Float,
    val quietDb: Float,
    val dc: Float,
    /** Share of samples at full scale. */
    val clipped: Float,
    /** Zero crossings per second. */
    val zcr: Float,
    /** Share of the speech frames' energy in 0–300, 300–1k, 1–2k, 2–4k and 4–8k Hz, in %. */
    val bands: List<Int>,
) {
    companion object {
        private const val RATE = 16_000
        private const val FRAME = 320
        private const val FFT = 512
        private val EDGES = intArrayOf(0, 300, 1_000, 2_000, 4_000, 8_000)

        fun of(pcm: FloatArray): AudioStats {
            val n = pcm.size
            if (n == 0) return AudioStats(0f, -120f, -120f, -120f, 0f, 0f, 0f, List(EDGES.size - 1) { 0 })
            var peak = 0f
            var sum = 0.0
            var clipped = 0
            var crossings = 0
            for (i in 0 until n) {
                val x = pcm[i]
                peak = maxOf(peak, abs(x))
                sum += x
                if (abs(x) >= 0.99f) clipped++
                if (i > 0 && (pcm[i - 1] < 0f) != (x < 0f)) crossings++
            }
            val frames = n / FRAME
            val rms = FloatArray(maxOf(1, frames)) { f ->
                var s = 0.0
                for (i in f * FRAME until minOf(n, (f + 1) * FRAME)) s += pcm[i] * pcm[i]
                sqrt(s / FRAME).toFloat()
            }
            val sorted = rms.sortedArray()
            val loud = sorted[((sorted.size - 1) * 0.9).toInt()]
            val quiet = sorted[((sorted.size - 1) * 0.1).toInt()]
            // Spectrum of the louder half of the frames (the speech), Hann-windowed.
            val median = sorted[sorted.size / 2]
            val energy = DoubleArray(EDGES.size - 1)
            val re = DoubleArray(FFT)
            val im = DoubleArray(FFT)
            var start = 0
            while (start + FFT <= n) {
                val centre = minOf(rms.size - 1, (start + FFT / 2) / FRAME)
                if (rms[centre] >= median) {
                    for (i in 0 until FFT) {
                        re[i] = pcm[start + i] * (0.5 - 0.5 * cos(2 * PI * i / (FFT - 1)))
                        im[i] = 0.0
                    }
                    fft(re, im)
                    for (k in 1 until FFT / 2) {
                        val hz = k * RATE / FFT
                        val band = (0 until EDGES.size - 1).firstOrNull { hz >= EDGES[it] && hz < EDGES[it + 1] } ?: continue
                        energy[band] += re[k] * re[k] + im[k] * im[k]
                    }
                }
                start += FFT / 2
            }
            val total = energy.sum().takeIf { it > 0 } ?: 1.0
            return AudioStats(
                seconds = n / RATE.toFloat(),
                peakDb = db(peak), loudDb = db(loud), quietDb = db(quiet),
                dc = (sum / n).toFloat(),
                clipped = clipped / n.toFloat(),
                zcr = crossings * RATE / n.toFloat(),
                bands = energy.map { (it * 100 / total).toInt() },
            )
        }

        private fun db(x: Float): Float = (20 * log10(maxOf(x, 1e-6f).toDouble())).toFloat()

        /** In place, radix 2. */
        private fun fft(re: DoubleArray, im: DoubleArray) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
                j = j or bit
                if (i < j) {
                    re[i] = re[j].also { re[j] = re[i] }
                    im[i] = im[j].also { im[j] = im[i] }
                }
            }
            var len = 2
            while (len <= n) {
                val angle = -2 * PI / len
                for (i in 0 until n step len) {
                    for (k in 0 until len / 2) {
                        val wr = cos(angle * k)
                        val wi = sin(angle * k)
                        val xr = re[i + k + len / 2] * wr - im[i + k + len / 2] * wi
                        val xi = re[i + k + len / 2] * wi + im[i + k + len / 2] * wr
                        re[i + k + len / 2] = re[i + k] - xr
                        im[i + k + len / 2] = im[i + k] - xi
                        re[i + k] += xr
                        im[i + k] += xi
                    }
                }
                len = len shl 1
            }
        }
    }
}
