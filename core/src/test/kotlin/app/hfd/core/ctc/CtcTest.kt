package app.hfd.core.ctc

import app.hfd.core.Assets
import app.hfd.core.HfdJson
import app.hfd.core.quran.AyahRef
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The CTC recogniser's own parts against the reference implementation (NeMo's front end in numpy,
 * the model in ONNX Runtime: tools/ctc), on the app's reference clip (al-Ikhlāṣ 112:1, Muʿayqilī).
 */
class CtcTest {
    private fun resource(name: String): ByteArray = javaClass.getResourceAsStream("/ctc/$name")!!.readBytes()

    private val vocabulary = CtcVocabulary(HfdJson.decodeFromString(ListSerializer(String.serializer()), resource("vocab.json").decodeToString()))

    /** What the model said of the clip: 74 frames × 63 symbols. */
    private val output: CtcOutput = run {
        val b = ByteBuffer.wrap(resource("112001.logprobs")).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val values = FloatArray(b.remaining()).also { b.get(it) }
        CtcOutput(values, values.size / 63, 63)
    }

    private val clip: FloatArray = run {
        val bytes = Assets.file("diag/112001.wav").readBytes()
        var at = 12
        while (String(bytes, at, 4, Charsets.US_ASCII) != "data") at += 8 + ByteBuffer.wrap(bytes, at + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val b = ByteBuffer.wrap(bytes, at + 8, bytes.size - at - 8).order(ByteOrder.LITTLE_ENDIAN)
        FloatArray((bytes.size - at - 8) / 2) { b.short / 32768f }
    }

    /** 112:1 without its basmala, as Tanzil writes it (the order of the signs matters). */
    private val aya = Assets.quran.text(AyahRef(112, 1))!!

    @Test
    fun theFiltersAreSlaneys() {
        val f = Mel.slaneyFilters()
        // Unit area, as far as a narrow band's few bins show it: weights times the bin width.
        for (m in 0 until Mel.MELS) {
            var sum = 0.0
            for (k in 0 until Mel.BINS) sum += f[m * Mel.BINS + k]
            assertEquals("band $m", 1.0, sum * 16000 / Mel.FFT, 0.2)
        }
        // The model's own file (fb.bin): its largest weight.
        assertEquals(0.026406592f, f.max(), 1e-6f)
    }

    @Test
    fun theSpectrogramIsNemos() {
        val f = Mel().features(clip)
        assertEquals(46672, clip.size)
        assertEquals(292, f.frames)
        assertEquals(291, f.length)
        val expected = mapOf(
            (0 to 0) to -1.0239966f, (0 to 1) to -1.0235729f, (5 to 10) to 0.4822462f, (40 to 100) to -0.7470878f,
            (79 to 290) to -1.8706577f, (79 to 291) to 0f, (20 to 200) to 1.3744208f, (63 to 37) to -1.1925009f,
        )
        for ((at, value) in expected) assertEquals("band ${at.first} frame ${at.second}", value, f.data[at.first * f.frames + at.second], 2e-3f)
    }

    @Test
    fun readsTheClip() {
        assertEquals(74, output.frames)
        assertEquals(aya, Ctc.greedy(output, vocabulary))
    }

    @Test
    fun theTextSaidCostsNothingAndAnotherWordDoes() {
        val right = Ctc.forced(output, vocabulary, aya)!!
        assertEquals(4, right.mean.size)
        assertTrue(right.mean.all { it < 0.01f } && right.worst.all { it < 0.01f })
        // Each word after the one before, inside the utterance.
        assertTrue((0 until 4).all { right.from[it] <= right.to[it] } && (1 until 4).all { right.from[it] > right.to[it - 1] })
        // «ٱلصَّمَدُ» (112:2) held where «أَحَدٌ» was said.
        val wrong = aya.split(' ').take(3).joinToString(" ") + " " + Assets.quran.text(AyahRef(112, 2))!!.split(' ').last()
        val cost = Ctc.forced(output, vocabulary, wrong)!!
        assertTrue(cost.mean.take(3).all { it < 0.01f })
        assertEquals(7.823f, cost.mean[3], 0.05f)
        assertEquals(27.412f, cost.worst[3], 0.05f)
    }

    @Test
    fun aTextLongerThanTheUtteranceHasNoAlignment() {
        val long = (1..40).joinToString(" ") { aya }
        assertNull(Ctc.forced(output, vocabulary, long))
    }
}
