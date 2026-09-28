package app.hfd.recite

import app.hfd.core.progress.Event
import app.hfd.core.quran.AyahRef
import app.hfd.core.recite.Arabic
import app.hfd.core.recite.AyahResult
import app.hfd.core.recite.ReciteTarget
import app.hfd.core.recite.Tracker
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
    /** Chunks of speech waiting for recognition. */
    val pending: Int = 0,
    /** The last thing heard, as recognised (shown small, for trust). */
    val heard: String? = null,
    val results: List<AyahResult> = emptyList(),
    val error: String? = null,
) {
    val done: Boolean get() = next == null
}

/**
 * Reciting [targets] aloud from memory, Tarteel-style: the microphone is cut into chunks at the
 * pauses, each chunk is recognised on the phone (whisper.cpp, Tarteel's model) and followed on
 * the text by [Tracker]; every āya finished is written to the progress log ([record]) with its
 * mistakes and the rating that follows. Main-thread API.
 */
class ReciteSession(
    val fadilaId: String,
    val targets: List<ReciteTarget>,
    private val scope: CoroutineScope,
    private val model: () -> File?,
    private val record: (Event) -> Unit,
) {
    private val tracker = Tracker(targets)
    private val recorder = Recorder()
    private val recognizer = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val chunks = Channel<FloatArray>(Channel.UNLIMITED)
    private var whisper: Whisper? = null
    private var listenJob: Job? = null
    private val consumer: Job
    private var logged = 0
    private var ayahStartedAt = System.currentTimeMillis()

    private val _ui = MutableStateFlow(snapshot(ReciteUi(targets, emptyList(), null)))
    val ui: StateFlow<ReciteUi> = _ui.asStateFlow()
    val level: StateFlow<Float> = recorder.level

    init {
        // Recognition runs one chunk at a time, in order, while the next is being recorded.
        consumer = scope.launch {
            for (chunk in chunks) {
                val text = withContext(recognizer) { loadedWhisper()?.transcribe(chunk) }
                _ui.value = _ui.value.copy(pending = (_ui.value.pending - 1).coerceAtLeast(0))
                if (text != null) onHeard(text)
            }
        }
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
                recorder.chunks().collect { chunk ->
                    _ui.value = _ui.value.copy(pending = _ui.value.pending + 1)
                    chunks.send(chunk)
                }
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

    /** Shows the next word; it counts as a mistake. */
    fun hint() {
        tracker.hint() ?: return
        afterProgress(null)
    }

    /** A chunk of recognised speech (also used by the screenshot test). */
    fun onHeard(text: String) {
        val clean = withoutOpening(text)
        val before = tracker.position
        val moved = tracker.feed(clean)
        Diag.log("recite.heard", "text" to text, "clean" to clean, "from" to before, "to" to tracker.position, "moved" to moved, "done" to tracker.done)
        afterProgress(text.ifBlank { null })
    }

    fun close() {
        Diag.log("recite.close", "position" to tracker.position, "done" to tracker.done)
        recorder.stop()
        listenJob?.cancel()
        consumer.cancel()
        chunks.close()
        // Freed on the recognition thread, after any chunk still being recognised.
        scope.launch(recognizer) {
            whisper?.close()
            whisper = null
        }.invokeOnCompletion { recognizer.close() }
    }

    private fun afterProgress(heard: String?) {
        val finished = tracker.finished()
        val now = System.currentTimeMillis()
        for (r in finished.drop(logged)) {
            Diag.log("recite.ayah", "ref" to r.ref.key, "words" to r.words, "mistakes" to r.mistakes.joinToString(","), "rating" to r.rating.name)
            record(Event.Recite(now, r.ref.key, r.words, r.mistakes, r.rating, now - ayahStartedAt))
            ayahStartedAt = now
        }
        logged = finished.size
        if (tracker.done) recorder.stop()
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
        return Whisper.load(file.path).also { whisper = it }
    }

    /**
     * Drops the isti'ādha and, at the start of a sūra, the basmala: recited before the text but
     * not part of it here.
     */
    private fun withoutOpening(text: String): String {
        var words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        fun startsWith(phrase: String): Int {
            val p = Arabic.words(phrase).map { Arabic.skeleton(it) }
            val w = words.take(p.size).map { Arabic.skeleton(it) }
            return if (w == p) p.size else 0
        }
        words = words.drop(startsWith(ISTIADHA))
        if (!tracker.done) {
            val (a, w) = tracker.locate(tracker.position)
            val ref = targets[a].ref
            if (w == 0 && ref.aya == 1 && ref.sura != 1 && ref.sura != 9) words = words.drop(startsWith(BASMALA))
        }
        return words.joinToString(" ")
    }

    companion object {
        const val ERROR_MODEL = "model"
        const val ERROR_MIC = "mic"
        private const val ISTIADHA = "أعوذ بالله من الشيطان الرجيم"
        private const val BASMALA = "بسم الله الرحمن الرحيم"

        fun targets(refs: List<AyahRef>, text: (AyahRef) -> String?): List<ReciteTarget> =
            refs.map { ReciteTarget(it, Arabic.words(text(it).orEmpty())) }
    }
}
