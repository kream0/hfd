package app.hfd.recite

import android.annotation.SuppressLint
import android.media.AudioFormat
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

    /** Opens and starts [source] (an Android audio source), or null where the phone doesn't offer it. */
    @SuppressLint("MissingPermission")
    private fun open(source: Int): AudioRecord? {
        val rate = Segmenter.RATE
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = runCatching {
            AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, rate) * 2)
        }.getOrNull()
        Diag.log("mic.open", "source" to nameOf(source), "state" to record?.state, "minBuffer" to minBuf)
        if (record == null) return null
        if (record.state != AudioRecord.STATE_INITIALIZED || runCatching { record.startRecording() }.isFailure ||
            record.recordingState != AudioRecord.RECORDSTATE_RECORDING
        ) {
            record.release()
            return null
        }
        // Which microphone Android gives: the phone's, or a headset's (earbuds).
        record.routedDevice.let { d ->
            Diag.log("mic.device", "source" to nameOf(source), "type" to d?.type, "name" to d?.productName?.toString(), "id" to d?.id, "rate" to record.sampleRate)
        }
        return record
    }

    /**
     * Utterances until the collector stops. Needs RECORD_AUDIO. The microphone is the first of
     * [sources] (Android audio sources) the phone opens; if the first stretch of speech it gives
     * is muffled (hardly anything above 1 kHz: [Clarity.isMuffled]), the next one is tried, and
     * so on; the first clear one stays and goes to [clear] (to start with it next time). If none
     * is, the least muffled one stays: the phone itself is covered (a pocket, a hand). With
     * [capture], the whole session as the microphone gave it (up to [CAPTURE_SECONDS]) is handed
     * to it at the end, to replay it on the bench (the owner's opt-in recordings).
     */
    fun utterances(
        capture: ((FloatArray) -> Unit)? = null,
        sources: List<Int> = SOURCES,
        clear: (Int) -> Unit = {},
    ): Flow<Utterance> = flow {
        val rate = Segmenter.RATE
        var si = 0
        var record: AudioRecord? = null
        while (si < sources.size && record == null) record = open(sources[si]).also { if (it == null) si++ }
        checkNotNull(record) { "microphone unavailable" }
        // How muffled each source tried was (share of the voice's energy above 1 kHz); settled once one is clear.
        val shares = HashMap<Int, Double>()
        var settled = false
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
                val n = record!!.read(frame, 0, frame.size)
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
                var next: Int? = null
                for (u in out) {
                    if (u.final) {
                        finals++
                        Diag.log("mic.utterance", "id" to u.id, "seconds" to u.seconds)
                        // Judge the microphone on a real stretch of speech.
                        if (!settled && u.seconds >= JUDGE_SECONDS) {
                            val source = sources[si]
                            val share = Clarity.highShare(u.pcm)
                            shares[source] = share
                            Diag.log("mic.quality", "source" to nameOf(source), "highShare" to share, "muffled" to (share < Clarity.MUFFLED_SHARE))
                            when {
                                share >= Clarity.MUFFLED_SHARE -> { settled = true; clear(source) }
                                si + 1 < sources.size -> next = si + 1
                                else -> {
                                    // All muffled: back to the least muffled one.
                                    settled = true
                                    val best = shares.maxByOrNull { it.value }!!.key
                                    if (best != source) next = sources.indexOf(best)
                                }
                            }
                        }
                    }
                    emit(u)
                }
                // Another microphone: the voice goes on in the same utterance flow.
                var to = next
                while (to != null && to < sources.size) {
                    val opened = open(sources[to])
                    if (opened != null) {
                        runCatching { record!!.stop() }
                        record!!.release()
                        record = opened
                        Diag.log("mic.switch", "from" to nameOf(sources[si]), "to" to nameOf(sources[to]))
                        si = to
                        break
                    }
                    to = if (settled) null else to + 1
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
            runCatching { record?.stop() }
            record?.release()
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
        /**
         * The microphones tried, in order: speech recognition's (the default), unprocessed, the
         * plain microphone, the camcorder's (often another capsule), live performance's.
         */
        val SOURCES = listOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.CAMCORDER,
            MediaRecorder.AudioSource.VOICE_PERFORMANCE,
        )

        fun nameOf(source: Int): String = when (source) {
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
            MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
            MediaRecorder.AudioSource.MIC -> "MIC"
            MediaRecorder.AudioSource.CAMCORDER -> "CAMCORDER"
            MediaRecorder.AudioSource.VOICE_PERFORMANCE -> "VOICE_PERFORMANCE"
            else -> source.toString()
        }

        /** A stretch of speech at least this long tells whether a microphone is muffled. */
        private const val JUDGE_SECONDS = 1.5f
        /** The longest session recording sent (the owner's opt-in): 4 min, 7.7 MB. */
        private const val CAPTURE_SECONDS = 240
        /** A bar every 60 ms; 64 of them, about four seconds. */
        private const val WAVE_FRAMES = 3
        private const val WAVE_BARS = 64

        private fun dB(rms: Float): Float = (20 * kotlin.math.log10(rms.coerceAtLeast(1e-6f).toDouble())).toFloat()
    }
}
