package app.hfd.core.recite

import kotlin.math.PI
import kotlin.math.cos
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

    /**
     * What the recogniser gets: [pcm] at a normal level ([Level]); with [boost], a muffled voice's
     * highs raised first (kept for the bench to compare: it doesn't help).
     */
    fun prepare(pcm: FloatArray, boost: Boolean = false): FloatArray {
        if (!boost) return Level.normalize(pcm).first
        val share = highShare(pcm)
        return Level.normalize(if (share < MUFFLED_SHARE) highShelf(pcm, db = boostFor(share)) else pcm).first
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
