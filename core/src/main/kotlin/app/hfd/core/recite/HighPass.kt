package app.hfd.core.recite

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A 4th-order Butterworth high-pass (two biquads, state kept between frames): takes away the
 * rumble below the voice — handling, breath on the microphone, a hand or a pocket moving —
 * which on the owner's phone was louder than the voice (94–99 % of the energy below 300 Hz): the
 * level correction raised the rumble instead of the voice, and the voice detection took it for
 * speech. The voice itself keeps its sounds; the recogniser doesn't need what lies below 120 Hz.
 */
class HighPass(cutoff: Double = CUTOFF_HZ, rate: Int = Segmenter.RATE) {
    private class Section(q: Double, cutoff: Double, rate: Int) {
        private val b0: Double
        private val b1: Double
        private val b2: Double
        private val a1: Double
        private val a2: Double
        private var s1 = 0.0
        private var s2 = 0.0

        init {
            val w0 = 2 * PI * cutoff / rate
            val cw = cos(w0)
            val alpha = sin(w0) / (2 * q)
            val a0 = 1 + alpha
            b0 = (1 + cw) / 2 / a0
            b1 = -(1 + cw) / a0
            b2 = (1 + cw) / 2 / a0
            a1 = -2 * cw / a0
            a2 = (1 - alpha) / a0
        }

        fun step(x: Double): Double {
            val y = b0 * x + s1
            s1 = b1 * x - a1 * y + s2
            s2 = b2 * x - a2 * y
            return y
        }
    }

    private val sections = listOf(Section(0.5412, cutoff, rate), Section(1.3066, cutoff, rate))

    fun filter(frame: FloatArray): FloatArray = FloatArray(frame.size) { i ->
        var y = frame[i].toDouble()
        for (s in sections) y = s.step(y)
        y.toFloat()
    }

    companion object {
        const val CUTOFF_HZ = 120.0
    }
}
