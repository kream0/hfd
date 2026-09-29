package app.hfd.recite

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import app.hfd.core.recite.Clarity
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
 * The microphone Recite listens with: a headset's ([name], e.g. the owner's earbuds) or the
 * phone's; [headsetAvailable] when earbuds with a microphone are connected (to offer them).
 */
data class Mic(val headset: Boolean, val name: String?, val headsetAvailable: Boolean)

/**
 * The microphone, as utterances for the recogniser ([Segmenter]): the speech so far every second
 * while the reciter goes on, then whole at the pause. 16 kHz mono floats. With [audio], connected
 * earbuds' microphone is used ([HeadsetMic]).
 */
class Recorder(private val audio: AudioManager? = null) {
    private val _level = MutableStateFlow(0f)
    /** Voice level 0…1, for the listening indicator. */
    val level: StateFlow<Float> = _level.asStateFlow()

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private val _wave = MutableStateFlow(Wave.EMPTY)
    /** The live waveform shown while listening, newest bar last. */
    val wave: StateFlow<Wave> = _wave.asStateFlow()

    private val _mic = MutableStateFlow<Mic?>(null)
    /** The microphone in use while listening. */
    val mic: StateFlow<Mic?> = _mic.asStateFlow()

    private val stopRequested = AtomicBoolean(false)
    private val restartRequested = AtomicBoolean(false)

    /** Ends [utterances] after handing over what was being said (unlike cancelling, which drops it). */
    fun stop() = stopRequested.set(true)

    /** Drops what is being said and starts a new utterance (after a hint). */
    fun restart() = restartRequested.set(true)

    /** Opens and starts [source] (an Android audio source) on [device] if given, or null if the phone refuses. */
    @SuppressLint("MissingPermission")
    private fun open(source: Int, device: AudioDeviceInfo?): AudioRecord? {
        val rate = Segmenter.RATE
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = runCatching {
            AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, rate) * 2)
        }.getOrNull()
        Diag.log("mic.open", "source" to nameOf(source), "state" to record?.state, "minBuffer" to minBuf, "preferred" to device?.type)
        if (record == null) return null
        if (device != null) runCatching { record.setPreferredDevice(device) }
        if (record.state != AudioRecord.STATE_INITIALIZED || runCatching { record.startRecording() }.isFailure ||
            record.recordingState != AudioRecord.RECORDSTATE_RECORDING
        ) {
            record.release()
            return null
        }
        return record
    }

    /**
     * Utterances until the collector stops. Needs RECORD_AUDIO. With [headset] and earbuds with a
     * microphone connected, they are listened to; otherwise the phone's microphone. Its first
     * stretch of speech tells how muffled it is (diagnostics). With [capture], the whole session as
     * the microphone gave it (up to [CAPTURE_SECONDS]) is handed to it at the end, to replay it on
     * the bench (the owner's opt-in recordings).
     */
    fun utterances(capture: ((FloatArray) -> Unit)? = null, headset: Boolean = true): Flow<Utterance> = flow {
        val rate = Segmenter.RATE
        val link = audio?.let(::HeadsetMic)
        val earbuds = link?.available()
        val linked = headset && link != null && earbuds != null && link.connect(earbuds)
        val onEarbuds = if (linked) open(MediaRecorder.AudioSource.VOICE_COMMUNICATION, link!!.input(earbuds!!)) else null
        if (linked && onEarbuds == null) link!!.release()
        val record = checkNotNull(
            onEarbuds ?: open(MediaRecorder.AudioSource.VOICE_RECOGNITION, null) ?: open(MediaRecorder.AudioSource.MIC, null),
        ) { "microphone unavailable" }
        // Which microphone Android gives: the phone's, or the earbuds'.
        val routed = record.routedDevice
        Diag.log(
            "mic.device", "source" to nameOf(record.audioSource), "type" to routed?.type, "name" to routed?.productName?.toString(),
            "id" to routed?.id, "rate" to record.sampleRate, "earbuds" to earbuds?.productName?.toString(),
        )
        val onHeadset = onEarbuds != null && (routed == null || HeadsetMic.isHeadset(routed))
        _mic.value = Mic(onHeadset, if (onHeadset) (routed ?: earbuds)?.productName?.toString() else null, earbuds != null)
        var judged = false
        val frame = ShortArray(Segmenter.FRAME)
        val seg = Segmenter()
        val captured = if (capture != null) ShortArray(CAPTURE_SECONDS * rate) else null
        var capturedSize = 0
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
            while (currentCoroutineContext().isActive && !stopRequested.get()) {
                val n = record.read(frame, 0, frame.size)
                if (n <= 0) continue
                if (captured != null && capturedSize + n <= captured.size) {
                    frame.copyInto(captured, capturedSize, 0, n)
                    capturedSize += n
                }
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
                        // How clear the microphone is, on a real stretch of speech.
                        if (!judged && u.seconds >= JUDGE_SECONDS) {
                            judged = true
                            val share = Clarity.highShare(u.pcm)
                            Diag.log("mic.quality", "source" to nameOf(record.audioSource), "headset" to onHeadset, "highShare" to share, "muffled" to (share < Clarity.MUFFLED_SHARE))
                        }
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
            if (onEarbuds != null) link!!.release()
            _mic.value = null
            _level.value = 0f
            _speaking.value = false
            _wave.value = Wave.EMPTY
            if (captured != null && capturedSize > 0) {
                val pcm = FloatArray(capturedSize) { captured[it] / 32768f }
                capture?.let { handOver -> Thread { handOver(pcm) }.start() }
            }
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        fun nameOf(source: Int): String = when (source) {
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
            MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
            MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
            MediaRecorder.AudioSource.MIC -> "MIC"
            else -> source.toString()
        }

        /** A stretch of speech at least this long tells whether the microphone is muffled. */
        private const val JUDGE_SECONDS = 1.5f
        /** The longest session recording sent (the owner's opt-in): 4 min, 7.7 MB. */
        private const val CAPTURE_SECONDS = 240
        /** A bar every 60 ms; 64 of them, about four seconds. */
        private const val WAVE_FRAMES = 3
        private const val WAVE_BARS = 64

        private fun dB(rms: Float): Float = (20 * kotlin.math.log10(rms.coerceAtLeast(1e-6f).toDouble())).toFloat()
    }
}
