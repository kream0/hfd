package app.hfd.recite

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import app.hfd.core.recite.Segmenter
import app.hfd.core.recite.Utterance
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

/** The last few seconds of the microphone, as bars: each one's level (0…1) and whether it was voice. */
class Wave(val levels: FloatArray, val voiced: BooleanArray) {
    companion object {
        val EMPTY = Wave(FloatArray(0), BooleanArray(0))
    }
}

/**
 * The microphone, as utterances for the recogniser ([Segmenter]): the speech so far every second
 * while the reciter goes on, then whole at the pause. 16 kHz mono floats.
 */
class Recorder {
    private val _level = MutableStateFlow(0f)
    /** Voice level 0…1, for the listening indicator. */
    val level: StateFlow<Float> = _level.asStateFlow()

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private val _wave = MutableStateFlow(Wave.EMPTY)
    /** The live waveform shown while listening, newest bar last. */
    val wave: StateFlow<Wave> = _wave.asStateFlow()

    private val stopRequested = AtomicBoolean(false)
    private val restartRequested = AtomicBoolean(false)

    /** Ends [utterances] after handing over what was being said (unlike cancelling, which drops it). */
    fun stop() = stopRequested.set(true)

    /** Drops what is being said and starts a new utterance (after a hint). */
    fun restart() = restartRequested.set(true)

    /** Utterances until the collector stops. Needs RECORD_AUDIO. */
    @SuppressLint("MissingPermission")
    fun utterances(): Flow<Utterance> = flow {
        val rate = Segmenter.RATE
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, rate) * 2,
        )
        Diag.log("mic.open", "state" to record.state, "minBuffer" to minBuf, "rate" to record.sampleRate, "source" to "VOICE_RECOGNITION")
        check(record.state == AudioRecord.STATE_INITIALIZED) { "microphone unavailable" }
        val frame = ShortArray(Segmenter.FRAME)
        val seg = Segmenter()
        stopRequested.set(false)
        restartRequested.set(false)
        // A second of levels at a time, for the diagnostics.
        var secFrames = 0
        var secSum = 0.0
        var secMax = 0f
        var secVoiced = 0
        var finals = 0
        // The waveform: a bar every WAVE_FRAMES frames, the last WAVE_BARS kept.
        val barLevels = ArrayDeque<Float>()
        val barVoiced = ArrayDeque<Boolean>()
        var barFrames = 0
        var barMax = 0f
        var barVoice = false
        _wave.value = Wave.EMPTY
        try {
            record.startRecording()
            while (currentCoroutineContext().isActive && !stopRequested.get()) {
                val n = record.read(frame, 0, frame.size)
                if (n <= 0) continue
                if (restartRequested.getAndSet(false)) seg.restart()
                val out = seg.feed(FloatArray(n) { frame[it] / 32768f })
                // −60 dB → 0, −15 dB → 1: phone microphones for speech recognition are quiet.
                _level.value = ((dB(seg.rms) + 60f) / 45f).coerceIn(0f, 1f)
                _speaking.value = seg.inSpeech
                barMax = maxOf(barMax, ((dB(seg.rms) + 62f) / 37f).coerceIn(0f, 1f))
                barVoice = barVoice || seg.voiced
                if (++barFrames == WAVE_FRAMES) {
                    barLevels.addLast(barMax)
                    barVoiced.addLast(barVoice)
                    if (barLevels.size > WAVE_BARS) { barLevels.removeFirst(); barVoiced.removeFirst() }
                    _wave.value = Wave(barLevels.toFloatArray(), barVoiced.toBooleanArray())
                    barFrames = 0; barMax = 0f; barVoice = false
                }
                secFrames++
                secSum += seg.rms
                secMax = maxOf(secMax, seg.rms)
                if (seg.voiced) secVoiced++
                if (secFrames == 50) {
                    Diag.log(
                        "mic.second", "avgDb" to dB((secSum / secFrames).toFloat()), "maxDb" to dB(secMax),
                        "noiseDb" to dB(seg.noise), "voiced" to secVoiced, "inSpeech" to seg.inSpeech, "utterances" to finals,
                    )
                    secFrames = 0; secSum = 0.0; secMax = 0f; secVoiced = 0
                }
                for (u in out) {
                    if (u.final) {
                        finals++
                        Diag.log("mic.utterance", "id" to u.id, "seconds" to u.seconds)
                    }
                    emit(u)
                }
            }
            // Stopped: what was being said still counts.
            seg.end()?.let { finals++; emit(it) }
            Diag.log("mic.stop", "utterances" to finals, "requested" to stopRequested.get())
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
            _wave.value = Wave.EMPTY
        }
    }.flowOn(Dispatchers.IO)

    private companion object {
        /** A bar every 60 ms; 64 of them, about four seconds. */
        const val WAVE_FRAMES = 3
        const val WAVE_BARS = 64

        fun dB(rms: Float): Float = (20 * kotlin.math.log10(rms.coerceAtLeast(1e-6f).toDouble())).toFloat()
    }
}
