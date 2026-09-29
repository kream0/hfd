package app.hfd.recite

import app.hfd.core.progress.Event
import app.hfd.core.quran.AyahRef
import app.hfd.core.recite.Arabic
import app.hfd.core.recite.AudioStats
import app.hfd.core.recite.AyahResult
import app.hfd.core.recite.Follower
import app.hfd.core.recite.Level
import app.hfd.core.recite.ReciteTarget
import app.hfd.core.recite.Utterance
import app.hfd.core.recite.WordStatus
import app.hfd.diag.Diag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/** What the Recite screen shows. */
data class ReciteUi(
    val targets: List<ReciteTarget>,
    /** Per āya, per word. */
    val status: List<List<WordStatus>>,
    /** Āya index and word index of the next word to recite; null when finished. */
    val next: Pair<Int, Int>?,
    val listening: Boolean = false,
    /** Loading the model (first start of the session). */
    val loading: Boolean = false,
    /** Whole utterances waiting for recognition. */
    val pending: Int = 0,
    /** The last thing heard, as recognised (shown small, for trust). */
    val heard: String? = null,
    val results: List<AyahResult> = emptyList(),
    val error: String? = null,
) {
    val done: Boolean get() = next == null
}

/**
 * Reciting [targets] aloud from memory, Tarteel-style: the microphone is cut into utterances
 * ([Recorder]), each recognised on the phone (whisper.cpp, Tarteel's model) again and again as
 * it grows, so the text follows the reciter as they go ([Follower]); every āya finished (on an
 * utterance's final reading) is written to the progress log ([record]) with its mistakes and the
 * rating that follows. Main-thread API.
 */
class ReciteSession(
    val fadilaId: String,
    val targets: List<ReciteTarget>,
    private val scope: CoroutineScope,
    private val model: () -> File?,
    private val record: (Event) -> Unit,
) {
    private val follower = Follower(targets)
    private val tracker = follower.tracker
    private val recorder = Recorder()
    private val recognizer = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    /** Utterances waiting: final ones in order, and a partial one replaced by the next reading. */
    private val queue = ArrayDeque<Utterance>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    /** The last utterance the recorder handed over. */
    private var lastId = -1
    private var whisper: Whisper? = null
    private var listenJob: Job? = null
    private val consumer: Job
    private var logged = 0
    private var heardChunks = 0
    private var ayahStartedAt = System.currentTimeMillis()

    private val _ui = MutableStateFlow(snapshot(ReciteUi(targets, emptyList(), null)))
    val ui: StateFlow<ReciteUi> = _ui.asStateFlow()
    val level: StateFlow<Float> = recorder.level

    init {
        // Recognition runs one reading at a time while the recorder goes on; a partial reading
        // left waiting is replaced by the newer one, so the text never lags far behind.
        consumer = scope.launch {
            for (x in wake) {
                while (true) {
                    val u = queue.removeFirstOrNull() ?: break
                    val text = withContext(recognizer) {
                        // The phone's speech microphone is faint: bring the voice to a normal level first.
                        val (pcm, gain) = Level.normalize(u.pcm)
                        if (u.final) {
                            heardChunks++
                            val st = AudioStats.of(u.pcm)
                            Diag.log(
                                "recite.chunk", "n" to heardChunks, "id" to u.id, "seconds" to st.seconds,
                                "gainDb" to (20 * kotlin.math.log10(gain.toDouble())).toFloat(),
                                "peakDb" to st.peakDb, "loudDb" to st.loudDb, "quietDb" to st.quietDb, "dc" to st.dc, "clipped" to st.clipped,
                                "zcr" to st.zcr, "bands" to st.bands.joinToString("/"),
                            )
                            Diag.attach("chunk$heardChunks", u.pcm, recordings())
                        }
                        loadedWhisper()?.transcribe(pcm)
                    }
                    if (u.final) _ui.value = _ui.value.copy(pending = (_ui.value.pending - 1).coerceAtLeast(0))
                    if (text != null) onHeard(u.id, text, u.final)
                }
            }
        }
    }

    private fun offer(u: Utterance) {
        lastId = maxOf(lastId, u.id)
        if (queue.lastOrNull()?.let { !it.final && it.id == u.id } == true) queue.removeLast()
        queue.addLast(u)
        if (u.final) _ui.value = _ui.value.copy(pending = _ui.value.pending + 1)
        wake.trySend(Unit)
    }

    /** Starts listening (the caller has the microphone permission). */
    fun start() {
        if (listenJob?.isActive == true || tracker.done) return
        val file = model()
        Diag.log("recite.start", "fadila" to fadilaId, "ayat" to targets.size, "from" to targets.firstOrNull()?.ref?.key, "model" to file?.length(), "done" to tracker.done)
        if (file == null) return
        _ui.value = _ui.value.copy(listening = true, error = null, loading = whisper == null)
        ayahStartedAt = System.currentTimeMillis()
        // Load the model now rather than on the first chunk.
        scope.launch {
            val w = withContext(recognizer) { loadedWhisper() }
            _ui.value = _ui.value.copy(loading = false, error = if (w == null) ERROR_MODEL else _ui.value.error)
            if (w == null) {
                Diag.log("recite.noModel")
                recorder.stop()
            }
        }
        listenJob = scope.launch {
            try {
                recorder.utterances().collect(::offer)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Diag.error("recite.mic", e)
                _ui.value = _ui.value.copy(error = ERROR_MIC)
            } finally {
                _ui.value = _ui.value.copy(listening = false)
            }
        }
    }

    /** Stops listening after what is being said has been handed over. */
    fun stop() = recorder.stop()

    /**
     * Shows the next word; it counts as a mistake. What was recited before stays, and the
     * recorder starts a new utterance from here.
     */
    fun hint() {
        follower.hint(through = lastId) ?: return
        recorder.restart()
        val dropped = queue.count { it.id <= lastId && it.final }
        queue.removeAll { it.id <= lastId }
        _ui.value = _ui.value.copy(pending = (_ui.value.pending - dropped).coerceAtLeast(0))
        afterProgress(null, final = true)
    }

    /** A whole utterance heard (the screenshot test). */
    fun onHeard(text: String) = onHeard(++lastId, text, final = true)

    /** A reading of utterance [id]: partial ones move the text on, the final one stays. */
    private fun onHeard(id: Int, text: String, final: Boolean) {
        val before = tracker.position
        val moved = follower.heard(id, text, final)
        Diag.log(if (final) "recite.heard" else "recite.partial", "id" to id, "text" to text, "from" to before, "to" to tracker.position, "moved" to moved, "done" to tracker.done)
        afterProgress(text.ifBlank { null }, final)
    }

    fun close() {
        Diag.log("recite.close", "position" to tracker.position, "done" to tracker.done)
        recorder.stop()
        listenJob?.cancel()
        consumer.cancel()
        wake.close()
        // Freed on the recognition thread, after any chunk still being recognised.
        scope.launch(recognizer) {
            whisper?.close()
            whisper = null
        }.invokeOnCompletion { recognizer.close() }
    }

    /** After a [final] reading (or a hint) the āyāt finished are recorded; a partial one only shows. */
    private fun afterProgress(heard: String?, final: Boolean) {
        val finished = tracker.finished()
        if (final) {
            val now = System.currentTimeMillis()
            for (r in finished.drop(logged)) {
                Diag.log("recite.ayah", "ref" to r.ref.key, "words" to r.words, "mistakes" to r.mistakes.joinToString(","), "rating" to r.rating.name)
                record(Event.Recite(now, r.ref.key, r.words, r.mistakes, r.rating, now - ayahStartedAt))
                ayahStartedAt = now
            }
            logged = finished.size
            if (tracker.done) recorder.stop()
        }
        _ui.value = snapshot(_ui.value.copy(heard = heard ?: _ui.value.heard, results = finished))
    }

    private fun snapshot(base: ReciteUi): ReciteUi = base.copy(
        status = targets.indices.map { a -> targets[a].words.indices.map { w -> tracker.statusOf(a, w) } },
        next = if (tracker.done) null else tracker.locate(tracker.position),
    )

    /** On the recognition thread only. */
    private fun loadedWhisper(): Whisper? {
        whisper?.let { return it }
        val file = model() ?: return null
        return Whisper.load(file.path).also {
            whisper = it
            if (it != null) selfTest(it)
        }
    }

    /**
     * Once a run: the model on a reference recitation (al-Ikhlāṣ 112:1, Alafasy), to tell a
     * recognition problem on the phone from a problem with what the microphone hears.
     */
    private fun selfTest(w: Whisper) {
        if (selfTested) return
        selfTested = true
        val pcm = referenceClip() ?: return
        val started = System.currentTimeMillis()
        val text = w.transcribe(pcm)
        Diag.log("whisper.selftest", "expected" to "قُلْ هُوَ ٱللَّهُ أَحَدٌ", "text" to text, "ms" to System.currentTimeMillis() - started)
    }

    companion object {
        @Volatile private var selfTested = false
        /** The reference clip for [selfTest], set by the app (a WAV in the assets). */
        @Volatile var referenceClip: () -> FloatArray? = { null }
        /** Whether the owner asked for recordings to be sent ([Diag.attach]). */
        @Volatile var recordings: () -> Boolean = { false }
        const val ERROR_MODEL = "model"
        const val ERROR_MIC = "mic"

        fun targets(refs: List<AyahRef>, text: (AyahRef) -> String?): List<ReciteTarget> =
            refs.map { ReciteTarget(it, Arabic.words(text(it).orEmpty())) }
    }
}
