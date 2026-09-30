package app.hfd.core.recite

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A muffled microphone. The owner's phone gave a voice with almost nothing above 1 kHz (under
 * 0.5 % of the energy, against 8–35 % for a normal voice), and the model then loses most words
 * (bench: ~97 % of words right on a clear voice, 10–60 % muffled). Bringing the highs back up
 * ([highShelf]) made it worse on the bench (it raises hiss more than consonants): recognition
 * takes the voice as it is ([prepare]), and a muffled one is pointed out and another microphone
 * tried instead ([isMuffled]).
 */
object Clarity {
    /** Share of the energy above 1 kHz under which speech counts as muffled (a normal voice: 8–35 %). */
    const val MUFFLED_SHARE = 0.03
    /** The share a muffled voice is brought back to. */
    const val TARGET_SHARE = 0.08
    const val SHELF_HZ = 1000.0
    const val MAX_DB = 30.0
    const val MATCH_UP_DB = 24.0
    const val MATCH_DOWN_DB = 12.0

    /** Third-octave band centres, 100 Hz – 6.3 kHz. */
    val CENTRES = doubleArrayOf(100.0, 125.0, 160.0, 200.0, 250.0, 315.0, 400.0, 500.0, 630.0, 800.0, 1000.0, 1250.0, 1600.0, 2000.0, 2500.0, 3150.0, 4000.0, 5000.0, 6300.0)
    private val THIRD = 2.0.pow(1.0 / 6)

    /**
     * A speaking voice's long-term spectrum in those bands, dB from its loudest (after the
     * international average, Byrne et al. 1994, male voices): flat 250–630 Hz, then falling.
     */
    val LTASS = doubleArrayOf(-12.0, -8.0, -5.0, -3.0, -1.0, 0.0, 0.0, 0.0, -1.0, -3.0, -5.0, -7.0, -9.0, -11.0, -13.0, -15.0, -17.0, -20.0, -23.0)

    /**
     * What the recogniser gets: [pcm] at a normal level ([Level]); with [boost], a muffled voice's
     * highs raised first (kept for the bench to compare: it doesn't help).
     */
    fun prepare(pcm: FloatArray, boost: Boolean = false, match: Boolean = false): FloatArray {
        if (match) return Level.normalize(match(pcm)).first
        if (!boost) return Level.normalize(pcm).first
        val share = highShare(pcm)
        return Level.normalize(if (share < MUFFLED_SHARE) highShelf(pcm, db = boostFor(share)) else pcm).first
    }

    /**
     * [pcm] with its long-term spectrum brought to a speaking voice's ([LTASS]): each third-octave
     * band raised or lowered by the difference (at most [MATCH_UP_DB] up, [MATCH_DOWN_DB] down),
     * smoothly between bands. The owner's voice reached the app ~20–25 dB darker than a reciter's
     * from 300 Hz up, on the phone's microphone and the earbuds' alike, with the sounds still
     * there (no cut-off): a gentle slope, which a shelf above 1 kHz didn't undo.
     */
    fun match(pcm: FloatArray, rate: Int = Segmenter.RATE): FloatArray {
        val level = ltas(pcm, rate) ?: return pcm
        val top = level.max()
        val gains = DoubleArray(CENTRES.size) { b -> (LTASS[b] - (level[b] - top)).coerceIn(-MATCH_DOWN_DB, MATCH_UP_DB) }
        var n = 1
        while (n < pcm.size) n = n shl 1
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        for (i in pcm.indices) re[i] = pcm[i].toDouble()
        AudioStats.fft(re, im)
        for (k in 0..n / 2) {
            val hz = k.toDouble() * rate / n
            val g = 10.0.pow(gainAt(hz, gains) / 20)
            re[k] *= g; im[k] *= g
            if (k in 1 until n / 2) { re[n - k] *= g; im[n - k] *= g }
        }
        // Inverse: the conjugate's transform, conjugated, over n.
        for (i in 0 until n) im[i] = -im[i]
        AudioStats.fft(re, im)
        return FloatArray(pcm.size) { (re[it] / n).toFloat() }
    }

    /** Third-octave levels (dB) of [pcm]'s louder frames (the voice), or null if too short. */
    fun ltas(pcm: FloatArray, rate: Int = Segmenter.RATE): DoubleArray? {
        val size = 512
        val frames = pcm.size / (size / 2) - 1
        if (frames < 4) return null
        val window = DoubleArray(size) { 0.5 - 0.5 * cos(2 * PI * it / (size - 1)) }
        val rms = DoubleArray(frames) { f ->
            var e = 0.0
            for (i in 0 until size) { val x = pcm[f * size / 2 + i].toDouble(); e += x * x }
            e
        }
        val cut = rms.sorted()[(frames * 0.6).toInt().coerceAtMost(frames - 1)]
        val power = DoubleArray(size / 2 + 1)
        val re = DoubleArray(size)
        val im = DoubleArray(size)
        for (f in 0 until frames) {
            if (rms[f] < cut) continue
            for (i in 0 until size) { re[i] = pcm[f * size / 2 + i] * window[i]; im[i] = 0.0 }
            AudioStats.fft(re, im)
            for (k in 0..size / 2) power[k] += re[k] * re[k] + im[k] * im[k]
        }
        return DoubleArray(CENTRES.size) { b ->
            val lo = CENTRES[b] / THIRD
            val hi = CENTRES[b] * THIRD
            var e = 1e-20
            for (k in 0..size / 2) { val hz = k.toDouble() * rate / size; if (hz >= lo && hz < hi) e += power[k] }
            10 * log10(e)
        }
    }

    /** The gain at [hz], between the bands' ([CENTRES]) on a log scale; none near 8 kHz. */
    private fun gainAt(hz: Double, gains: DoubleArray): Double {
        if (hz <= CENTRES.first()) return gains.first()
        if (hz >= CENTRES.last()) return gains.last() * ((7_600 - hz) / (7_600 - CENTRES.last())).coerceIn(0.0, 1.0)
        val b = CENTRES.indexOfFirst { it > hz }
        val t = ln(hz / CENTRES[b - 1]) / ln(CENTRES[b] / CENTRES[b - 1])
        return gains[b - 1] + (gains[b] - gains[b - 1]) * t
    }

    /** Hardly anything of [pcm] above 1 kHz (a pocket, a hand over the microphone…). */
    fun isMuffled(pcm: FloatArray): Boolean = highShare(pcm) < MUFFLED_SHARE

    /**
     * The shelf's gain for a voice with [share] of its energy above 1 kHz: what brings it to
     * [TARGET_SHARE], plus 6 dB (the shelf only reaches its full gain well above its corner).
     */
    fun boostFor(share: Double): Double =
        (10 * kotlin.math.log10(TARGET_SHARE / share.coerceAtLeast(1e-6)) + 6).coerceIn(6.0, MAX_DB)

    /** Share of [pcm]'s energy above 1 kHz (in its louder frames). */
    fun highShare(pcm: FloatArray): Double = AudioStats.of(pcm).shares.drop(2).sum()

    /** [pcm] with everything above [hz] raised by [db] (RBJ high shelf, slope 1). */
    fun highShelf(pcm: FloatArray, hz: Double = SHELF_HZ, db: Double = MAX_DB, rate: Int = Segmenter.RATE): FloatArray {
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
