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
    fun saysWhereItWroteEachCharacter() {
        val at = Ctc.written(output)
        assertEquals(aya.length, at.size)
        assertTrue((1 until at.size).all { at[it] > at[it - 1] } && at.first() >= 0 && at.last() < output.frames)
        // As the text held to the sound places them, a frame or two apart.
        val placed = Ctc.placed(output, vocabulary, aya)!!
        assertTrue(at.indices.all { kotlin.math.abs(at[it] - placed[it]) <= 2 })
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
    fun aShortVowelWrittenDifferentlyShowsWhereItIs() {
        // «هُوِ» held where «هُوَ» was said: that word's worst character is its last vowel, far over the bar.
        val words = aya.split(' ')
        assertEquals("\u0647\u064f\u0648\u064e", words[1])
        val changed = (words.take(1) + "\u0647\u064f\u0648\u0650" + words.drop(2)).joinToString(" ")
        val recognizer = CtcRecognizer({ output })
        val sounds = CtcRecognizer.sounds(recognizer.transcribe(clip, CtcRecognizer.SOUNDS + changed))!!
        assertEquals(4, sounds.size)
        assertTrue(sounds[1].first > CtcRecognizer.UNCLEAR)
        assertEquals(3, sounds[1].second)
        assertTrue(listOf(0, 2, 3).all { sounds[it].first < 1f })
        // As said: nothing over the bar.
        assertTrue(CtcRecognizer.sounds(recognizer.transcribe(clip, CtcRecognizer.SOUNDS + aya))!!.all { it.first < 1f })
        assertNull(CtcRecognizer.sounds(recognizer.transcribe(clip, CtcRecognizer.SOUNDS + (1..40).joinToString(" ") { aya })))
    }

    @Test
    fun theVocabularyIsTheModels() {
        assertEquals(vocabulary.characters, CtcVocabulary.QURAN.characters)
        assertEquals(62, CtcVocabulary.QURAN.blank)
    }

    @Test
    fun theRecogniserSpeaksTheFollowersProtocol() {
        // The model's answer for the clip, whatever it is given.
        val recognizer = CtcRecognizer({ output })
        assertEquals(aya, recognizer.transcribe(clip))
        assertEquals("", CtcRecognizer({ error("too short to run") }).transcribe(FloatArray(800)))
        // Asked to check a text: the reading, a tab, each word's cost.
        val right = recognizer.transcribe(clip, "?\n$aya")
        assertEquals(aya, right.substringBefore('\t'))
        assertEquals(listOf(0f, 0f, 0f, 0f), app.hfd.core.recite.Follower.Check.costs(right))
        val wrong = aya.split(' ').take(3).joinToString(" ") + " " + Assets.quran.text(AyahRef(112, 2))!!.split(' ').last()
        val costs = app.hfd.core.recite.Follower.Check.costs(recognizer.transcribe(clip, "?\n$wrong"))!!
        // «ٱلصَّمَدُ» where «أَحَدٌ» was said: far over the follower's tolerance.
        assertTrue(costs.take(3).all { it == 0f } && costs[3] > 3 * app.hfd.core.recite.Follower.CHECK_TOLERANCE)
        // A text the utterance has no room for: every word failed.
        val long = (1..40).joinToString(" ") { aya }
        assertTrue(app.hfd.core.recite.Follower.Check.costs(recognizer.transcribe(clip, "?\n$long"))!!.all { it == CtcRecognizer.FAILED })
    }

    @Test
    fun theLettersArePlacedInTime() {
        // Each character the model writes, at a frame of its own, in order; the words where the alignment has them.
        val placed = Ctc.placed(output, vocabulary, aya)!!
        val frames = aya.indices.filter { vocabulary.indexOf(aya[it]) != null }.map { placed[it] }
        assertTrue(frames.all { it in 0 until output.frames })
        assertEquals(frames, frames.sorted())
        assertEquals(frames.size, frames.distinct().size)
        val forced = Ctc.forced(output, vocabulary, aya)!!
        var at = 0
        aya.split(' ').forEachIndexed { w, word ->
            assertEquals("word $w", forced.from[w], placed[at])
            at += word.length + 1
        }
        // Asked through the recogniser: the frames' count, then each character's frame.
        val (count, again) = CtcRecognizer.times(CtcRecognizer({ output }).transcribe(clip, CtcRecognizer.TIMES + aya))!!
        assertEquals(74, count)
        assertEquals(placed.toList(), again.toList())
        assertNull(Ctc.placed(output, vocabulary, (1..40).joinToString(" ") { aya }))
    }

    @Test
    fun aTextLongerThanTheUtteranceHasNoAlignment() {
        val long = (1..40).joinToString(" ") { aya }
        assertNull(Ctc.forced(output, vocabulary, long))
    }
}
