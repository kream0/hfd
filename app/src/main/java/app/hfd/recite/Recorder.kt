package app.hfd.recite

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import app.hfd.diag.Diag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * The microphone, cut into chunks of speech: a chunk starts when the voice rises above the
 * room's noise (with a little audio before it) and ends at a pause, so each one holds a few
 * words to a few āyāt for the recogniser. 16 kHz mono floats.
 */
class Recorder {
    private val _level = MutableStateFlow(0f)
    /** Voice level 0…1, for the listening indicator. */
    val level: StateFlow<Float> = _level.asStateFlow()

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private val stopRequested = AtomicBoolean(false)

    /** Ends [chunks] after handing over what was being said (unlike cancelling, which drops it). */
    fun stop() = stopRequested.set(true)

    /** Chunks of speech until the collector stops. Needs RECORD_AUDIO. */
    @SuppressLint("MissingPermission")
    fun chunks(): Flow<FloatArray> = flow {
        val rate = Whisper.SAMPLE_RATE
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, rate) * 2,
        )
        Diag.log("mic.open", "state" to record.state, "minBuffer" to minBuf, "rate" to record.sampleRate, "source" to "VOICE_RECOGNITION")
        check(record.state == AudioRecord.STATE_INITIALIZED) { "microphone unavailable" }
        val frame = ShortArray(FRAME)
        val pre = ArrayDeque<FloatArray>()
        val chunk = ArrayList<FloatArray>()
        var noise = 0.01f
        var silentFrames = 0
        var voicedFrames = 0
        var inSpeech = false
        stopRequested.set(false)
        // A second of levels at a time, for the diagnostics.
        var secFrames = 0
        var secSum = 0.0
        var secMax = 0f
        var secVoiced = 0
        var sent = 0
        try {
            record.startRecording()
            while (currentCoroutineContext().isActive && !stopRequested.get()) {
                val n = record.read(frame, 0, FRAME)
                if (n <= 0) continue
                val f = FloatArray(n) { frame[it] / 32768f }
                var sum = 0.0
                for (x in f) sum += x * x
                val rms = sqrt(sum / n).toFloat()
                // The noise floor follows the quietest moments, slowly.
                noise = if (rms < noise) rms * 0.3f + noise * 0.7f else noise * 0.999f + rms * 0.001f
                val voiced = rms > maxOf(noise * VOICE_OVER_NOISE, MIN_RMS)
                // −60 dB → 0, −15 dB → 1: phone microphones for speech recognition are quiet.
                _level.value = ((dB(rms) + 60f) / 45f).coerceIn(0f, 1f)
                secFrames++
                secSum += rms
                secMax = maxOf(secMax, rms)
                if (voiced) secVoiced++
                if (secFrames == 50) {
                    Diag.log(
                        "mic.second", "avgDb" to dB((secSum / secFrames).toFloat()), "maxDb" to dB(secMax),
                        "noiseDb" to dB(noise), "voiced" to secVoiced, "inSpeech" to inSpeech, "chunks" to sent,
                    )
                    secFrames = 0; secSum = 0.0; secMax = 0f; secVoiced = 0
                }
                if (!inSpeech) {
                    pre.addLast(f)
                    if (pre.size > PRE_ROLL_FRAMES) pre.removeFirst()
                    if (voiced) {
                        inSpeech = true
                        _speaking.value = true
                        chunk.addAll(pre)
                        pre.clear()
                        silentFrames = 0
                        voicedFrames = 1
                    }
                    continue
                }
                chunk += f
                if (voiced) { silentFrames = 0; voicedFrames++ } else silentFrames++
                val long = chunk.size >= MAX_CHUNK_FRAMES
                if (silentFrames >= PAUSE_FRAMES || long) {
                    Diag.log("mic.chunk", "seconds" to chunk.size * FRAME / rate.toFloat(), "voiced" to voicedFrames, "sent" to (voicedFrames >= MIN_VOICED_FRAMES), "long" to long)
                    if (voicedFrames >= MIN_VOICED_FRAMES) { sent++; emit(join(chunk)) }
                    chunk.clear()
                    inSpeech = false
                    _speaking.value = false
                }
            }
            // Stopped: what was being said still counts.
            if (inSpeech && voicedFrames >= MIN_VOICED_FRAMES) emit(join(chunk))
            Diag.log("mic.stop", "chunks" to sent, "requested" to stopRequested.get())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Diag.error("mic.error", e)
            throw e
        } finally {
            runCatching { record.stop() }
            record.release()
            _level.value = 0f
            _speaking.value = false
        }
    }.flowOn(Dispatchers.IO)

    private fun join(frames: List<FloatArray>): FloatArray {
        val out = FloatArray(frames.sumOf { it.size })
        var at = 0
        for (f in frames) { f.copyInto(out, at); at += f.size }
        return out
    }

    companion object {
        /** 20 ms frames. */
        private const val FRAME = 320
        private const val PRE_ROLL_FRAMES = 15 // 300 ms kept before the voice starts
        private const val PAUSE_FRAMES = 35 // 700 ms of silence ends a chunk
        private const val MIN_VOICED_FRAMES = 10 // at least 200 ms of voice
        // 20 s at most: the model reads 30 s windows and slips beyond ~25 s (tools/model/evaluate.py).
        private const val MAX_CHUNK_FRAMES = 1000
        /** Voice: this much over the room's noise (×2.5 ≈ 8 dB), and above MIN_RMS (≈ −54 dB). */
        private const val VOICE_OVER_NOISE = 2.5f
        private const val MIN_RMS = 0.002f

        private fun dB(rms: Float): Float = (20 * kotlin.math.log10(rms.coerceAtLeast(1e-6f).toDouble())).toFloat()
    }
}
