package app.hfd.core.recite

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A muffled microphone made clearer before recognition. The owner's phone gave a voice with
 * almost nothing above 1 kHz (0 % of the energy, against 6–20 % in 1–2 kHz for a normal voice):
 * the consonants the model reads words by are there, but some 20–30 dB down. When a stretch of
 * speech is that muffled, a high shelf brings the frequencies above ~1 kHz back up.
 */
object Clarity {
    /** Share of the energy above 1 kHz under which speech counts as muffled (a normal voice: 8–35 %). */
    const val MUFFLED_SHARE = 0.03
    const val SHELF_HZ = 1000.0
    const val SHELF_DB = 20.0

    /** What the recogniser gets: [pcm] made clearer if it's muffled, then at a normal level ([Level]). */
    fun prepare(pcm: FloatArray, clarity: Boolean = true): FloatArray =
        Level.normalize(if (clarity && highShare(pcm) < MUFFLED_SHARE) highShelf(pcm) else pcm).first

    /** Share of [pcm]'s energy above 1 kHz (its first 30 s, in 512-sample windows). */
    fun highShare(pcm: FloatArray): Double {
        val stats = AudioStats.of(pcm)
        val total = stats.bands.sum().coerceAtLeast(1)
        return stats.bands.drop(2).sum().toDouble() / total
    }

    /** [pcm] with everything above [SHELF_HZ] raised by [SHELF_DB] (RBJ high shelf, slope 1). */
    fun highShelf(pcm: FloatArray, hz: Double = SHELF_HZ, db: Double = SHELF_DB, rate: Int = Segmenter.RATE): FloatArray {
        val a = 10.0.pow(db / 40)
        val w0 = 2 * PI * hz / rate
        val cw = cos(w0)
        val alpha = sin(w0) / 2 * sqrt(2.0)
        val sa = 2 * sqrt(a) * alpha
        val a0 = (a + 1) - (a - 1) * cw + sa
        val b0 = a * ((a + 1) + (a - 1) * cw + sa) / a0
        val b1 = -2 * a * ((a - 1) + (a + 1) * cw) / a0
        val b2 = a * ((a + 1) + (a - 1) * cw - sa) / a0
        val a1 = 2 * ((a - 1) - (a + 1) * cw) / a0
        val a2 = ((a + 1) - (a - 1) * cw - sa) / a0
        var s1 = 0.0
        var s2 = 0.0
        return FloatArray(pcm.size) { i ->
            val x = pcm[i].toDouble()
            val y = b0 * x + s1
            s1 = b1 * x - a1 * y + s2
            s2 = b2 * x - a2 * y
            y.toFloat()
        }
    }
}
