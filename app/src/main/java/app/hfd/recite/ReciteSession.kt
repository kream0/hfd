package app.hfd.recite

import android.media.AudioManager
import app.hfd.BuildConfig
import app.hfd.core.progress.Event
import app.hfd.core.quran.AyahRef
import app.hfd.core.recite.Arabic
import app.hfd.core.recite.AudioStats
import app.hfd.core.recite.AyahResult
import app.hfd.core.recite.Clarity
import app.hfd.core.recite.Follower
import app.hfd.core.recite.Level
import app.hfd.core.recite.MuffledWarning
import app.hfd.core.recite.ReciteTarget
import app.hfd.core.recite.Segmenter
import app.hfd.core.recite.Utterance
import app.hfd.core.recite.WordStatus
import app.hfd.diag.Diag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
    /** The microphone sounds muffled (a pocket, a hand over it) and the text doesn't follow ([MuffledWarning]). */
    val muffled: Boolean = false,
) {
    val done: Boolean get() = next == null
}

/**
 * Reciting [targets] aloud from memory, Tarteel-style: the microphone is cut into utterances
 * ([Recorder]), each recognised on the phone (whisper.cpp, Tarteel's model) again and again as
 * it grows, so the text follows the reciter as they go ([Follower]), word by word on screen. The
 * reciter may go back (start an āya over, say a phrase again) without it counting as a mistake:
 * the āyāt finished are written to the progress log ([record]) with their mistakes and the
 * rating that follows only at the end, as they stand then. Main-thread API.
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
    private val recorder = Recorder(audioManager())
    private val recognizer = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    /** Utterances waiting: final ones in order, and a partial one replaced by the next reading. */
    private val queue = ArrayDeque<Utterance>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    /** The last utterance the recorder handed over. */
    private var lastId = -1
    private var whisper: Whisper? = null
    private var listenJob: Job? = null
    private val consumer: Job
    private var heardChunks = 0
    private val muffledWarning = MuffledWarning()
    private val startedAt = System.currentTimeMillis()
    /** When each āya was first finished, and those already written to the log. */
    private val finishedAt = LinkedHashMap<AyahRef, Long>()
    private val recorded = HashSet<AyahRef>()
    /** Words shown as followed so far (flat index): they catch up with the tracker one at a time. */
    private var shown = 0
    private var reveal: Job? = null
    /**
     * Words right once a whole utterance was read (flat index): they stay so. Saying part of an
     * āya again takes the recitation back there, but what was recited right doesn't go grey again
     * (the owner), nor count as a mistake if a reading of it the second time goes wrong. Not from
     * partial readings: steered toward the text, one may light a word said differently a moment
     * before the whole utterance's reading marks it (the bench's swapped words).
     */
    private val wasRight = BooleanArray(tracker.size)
    /** Words right when a whole utterance was read (flat index): shown, they stay right ([wasRight]). */
    private val confirmed = BooleanArray(tracker.size)

    private val _ui = MutableStateFlow(snapshot(ReciteUi(targets, emptyList(), null)))
    val ui: StateFlow<ReciteUi> = _ui.asStateFlow()
    val level: StateFlow<Float> = recorder.level
    val wave: StateFlow<Wave> = recorder.wave
    /** The microphone listened with (the earbuds' or the phone's), while listening. */
    val mic: StateFlow<Mic?> = recorder.mic

    init {
        // Recognition runs one reading at a time while the recorder goes on; a partial reading
        // left waiting is replaced by the newer one, so the text never lags far behind.
        consumer = scope.launch {
            for (x in wake) {
                while (true) {
                    val u = queue.removeFirstOrNull() ?: break
                    // What the reciter may be saying from where this utterance began: the
                    // recogniser prefers it among what it nearly hears (a dark or faint voice).
                    val expected = follower.expected(u.id)
                    var muffled = false
                    val text = withContext(recognizer) {
                        // The phone's speech microphone is faint: at a normal level first (the bench
                        // does the same, Clarity.prepare).
                        val pcm = Clarity.prepare(u.pcm)
                        if (u.final) {
                            heardChunks++
                            val st = AudioStats.of(u.pcm)
                            val gain = Level.normalize(u.pcm).second
                            // Hardly anything above 1 kHz: a pocket, a hand over the microphone…
                            muffled = Clarity.isMuffled(u.pcm)
                            Diag.log(
                                "recite.chunk", "n" to heardChunks, "id" to u.id, "seconds" to st.seconds, "muffled" to muffled,
                                "gainDb" to (20 * kotlin.math.log10(gain.toDouble())).toFloat(),
                                "peakDb" to st.peakDb, "loudDb" to st.loudDb, "quietDb" to st.quietDb, "dc" to st.dc, "clipped" to st.clipped,
                                "zcr" to st.zcr, "bands" to st.bands.joinToString("/"),
                            )
                        }
                        loadedWhisper()?.transcribe(pcm, expected)
                    }
                    if (u.final) _ui.value = _ui.value.copy(pending = (_ui.value.pending - 1).coerceAtLeast(0))
                    val moved = if (text != null) onHeard(u.id, text, u.final, check = u.takeIf { it.final }) else 0
                    if (u.final) {
                        val warn = muffledWarning.heard(muffled, u.pcm.size / Segmenter.RATE.toFloat(), moved)
                        if (warn != _ui.value.muffled) _ui.value = _ui.value.copy(muffled = warn)
                    }
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
                // The owner's opt-in recordings: the whole session, to replay it on the bench.
                val capture: ((FloatArray) -> Unit)? = if (recordings()) { pcm ->
                    Diag.log("recite.session", "fadila" to fadilaId, "from" to targets.firstOrNull()?.ref?.key, "to" to targets.lastOrNull()?.ref?.key, "seconds" to pcm.size / 16_000f)
                    // The name says what was recited and when: session-<passage>-<from>-<to>-<HHmmss>,
                    // e.g. session-imran-opening-3_1-3_9-213243 (several in a run don't overwrite each other).
                    val range = listOfNotNull(targets.firstOrNull()?.ref, targets.lastOrNull()?.ref).joinToString("-") { "${it.sura}_${it.aya}" }
                    val time = java.text.SimpleDateFormat("HHmmss", java.util.Locale.ROOT).format(java.util.Date())
                    Diag.attach("session-$fadilaId-$range-$time", pcm, true)
                } else null
                // The recorder counts utterances from 0 each time it starts: they go on after this
                // session's (the follower ignores ids it is done with).
                val base = lastId + 1
                recorder.utterances(capture, headset()).collect { offer(Utterance(base + it.id, it.pcm, it.final)) }
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

    /** Listens again, on the microphone now chosen (earbuds or phone). */
    fun restartListening() {
        val job = listenJob?.takeIf { it.isActive } ?: return
        recorder.stop()
        scope.launch {
            job.join()
            start()
        }
    }

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
        // The hinted word shows at once.
        shown = maxOf(shown, tracker.position)
        afterProgress(null, final = true)
    }

    /** A whole utterance heard, shown at once (the screenshot test). */
    fun onHeard(text: String) {
        onHeard(++lastId, text, final = true)
        reveal?.cancel()
        shown = tracker.position
        _ui.value = snapshot(_ui.value)
    }

    /** A reading of utterance [id]: partial ones move the text on, the final one stays. */
    private fun onHeard(id: Int, text: String, final: Boolean): Int {
        val moved = follow(id, text, final)
        afterProgress(text.ifBlank { null }, final)
        return moved
    }

    /**
     * The same, from the recogniser: the mistakes a whole utterance ([check]) leaves are checked
     * first, by reading it again held to the text it covered (Follower.Check), before they show
     * or count. The phone's model misreads words said quickly or darkly (الحي القيوم read حيظهم,
     * 10 Oct): most of the owner's mistakes were the model's.
     */
    private suspend fun onHeard(id: Int, text: String, final: Boolean, check: Utterance?): Int {
        val moved = follow(id, text, final)
        if (check != null) follower.toCheck(id)?.let { c ->
            val again = withContext(recognizer) { loadedWhisper()?.transcribe(Clarity.prepare(check.pcm), c.expected) }
            val costs = again?.let(Follower.Check::costs)
            if (costs != null) {
                val right = follower.checked(c, costs)
                Diag.log("recite.check", "id" to id, "words" to c.words.joinToString(","), "right" to right, "reading" to again)
            }
        }
        afterProgress(text.ifBlank { null }, final)
        return moved
    }

    private fun follow(id: Int, text: String, final: Boolean): Int {
        val before = tracker.position
        val moved = follower.heard(id, text, final)
        Diag.log(if (final) "recite.heard" else "recite.partial", "id" to id, "text" to text, "from" to before, "to" to tracker.position, "moved" to moved, "done" to tracker.done)
        return moved
    }

    fun close() {
        Diag.log("recite.close", "position" to tracker.position, "done" to tracker.done)
        recordFinished()
        recorder.stop()
        reveal?.cancel()
        listenJob?.cancel()
        consumer.cancel()
        wake.close()
        // Freed on the recognition thread, after any chunk still being recognised.
        scope.launch(recognizer) {
            whisper?.let(::selfTest)
            whisper?.close()
            whisper = null
        }.invokeOnCompletion { recognizer.close() }
    }

    /** After a reading (or a hint): the text follows; at the end of the passage, the āyāt are recorded. */
    private fun afterProgress(heard: String?, final: Boolean) {
        if (final) for (w in 0 until tracker.position) if (tracker.status[w] == WordStatus.OK) confirmed[w] = true
        val finished = tracker.finished()
        if (final) {
            val now = System.currentTimeMillis()
            for (r in finished) finishedAt.putIfAbsent(r.ref, now)
            if (tracker.done) {
                recordFinished()
                recorder.stop()
            }
        }
        _ui.value = _ui.value.copy(heard = heard ?: _ui.value.heard, results = finished.map(::withoutRight))
        catchUp()
    }

    /**
     * The words shown follow the tracker one at a time, like a reading pointer (a reading often
     * brings several at once); going back shows at once.
     */
    private fun catchUp() {
        if (tracker.position <= shown) {
            shown = tracker.position
            _ui.value = snapshot(_ui.value)
            return
        }
        _ui.value = snapshot(_ui.value)
        if (reveal?.isActive == true) return
        reveal = scope.launch {
            while (shown < tracker.position) {
                val behind = tracker.position - shown
                shown++
                _ui.value = snapshot(_ui.value)
                delay((REVEAL_MS / behind).coerceIn(25L, 90L))
            }
        }
    }

    /** The āyāt finished, as they stand now, into the progress log (each once). */
    private fun recordFinished() {
        var previous = startedAt
        for (r in tracker.finished().map(::withoutRight)) {
            val at = finishedAt[r.ref] ?: System.currentTimeMillis()
            if (recorded.add(r.ref)) {
                Diag.log("recite.ayah", "ref" to r.ref.key, "words" to r.words, "mistakes" to r.mistakes.joinToString(","), "rating" to r.rating.name)
                record(Event.Recite(System.currentTimeMillis(), r.ref.key, r.words, r.mistakes, r.rating, (at - previous).coerceAtLeast(0)))
            }
            previous = at
        }
    }

    /** [r] without the mistakes on words that were right once ([wasRight]). */
    private fun withoutRight(r: AyahResult): AyahResult {
        val start = tracker.startOf(targets.indexOfFirst { it.ref == r.ref })
        return r.copy(mistakes = r.mistakes.filter { !wasRight[start + it] })
    }

    private fun snapshot(base: ReciteUi): ReciteUi {
        var flat = 0
        val status = targets.indices.map { a ->
            targets[a].words.indices.map { w ->
                val st = if (flat + w < shown) tracker.statusOf(a, w) else WordStatus.PENDING
                if (st == WordStatus.OK && confirmed[flat + w]) wasRight[flat + w] = true
                if (wasRight[flat + w]) WordStatus.OK else st
            }.also { flat += targets[a].words.size }
        }
        // The marker follows the voice through words said again (Follower.voice), else the text.
        val next = follower.voice ?: shown
        return base.copy(status = status, next = if (next >= tracker.size) null else tracker.locate(next))
    }

    /** On the recognition thread only. */
    private fun loadedWhisper(): Whisper? {
        whisper?.let { return it }
        val file = model() ?: return null
        return Whisper.load(file.path)?.also { whisper = it; npuOn(it) }
    }

    /**
     * The encoder on the NPU if this phone has a build of it ([NpuEncoder]), used once it reads the
     * reference clip (al-Ikhlāṣ 112:1) as it is: QNN set up, then one reading (some 150 ms).
     */
    private fun npuOn(w: Whisper) {
        val encoder = npuEncoder() ?: return
        val binary = encoder.file ?: return
        val pcm = referenceClip() ?: return
        if (!encoder.trying(BuildConfig.VERSION_CODE)) return
        try {
            if (w.setUpNpu(binary) != null) return
            val started = System.currentTimeMillis()
            val text = w.transcribe(pcm, onNpu = true)
            val right = text != null && Arabic.skeleton(text) == Arabic.skeleton(REFERENCE_TEXT)
            w.npu = right
            Diag.log("npu.selftest", "text" to text, "right" to right, "ms" to System.currentTimeMillis() - started)
        } finally {
            encoder.tried()
        }
    }

    /**
     * Once a run, when the session closes (not at its start, where it held up the first readings):
     * the model on a reference recitation (al-Ikhlāṣ 112:1, Alafasy), to tell a recognition
     * problem on the phone from a problem with what the microphone hears. (Threads, measured on
     * the owner's phone on 2 Oct: 4 take 1.38 s a reading, 6 take 1.47 s, 8 take 1.62 s.)
     */
    private fun selfTest(w: Whisper) {
        if (selfTested) return
        selfTested = true
        val pcm = referenceClip() ?: return
        val started = System.currentTimeMillis()
        val text = w.transcribe(pcm, onNpu = false)
        Diag.log("whisper.selftest", "expected" to REFERENCE_TEXT, "text" to text, "ms" to System.currentTimeMillis() - started)
    }

    companion object {
        @Volatile private var selfTested = false
        /** The reference clip for [selfTest], set by the app (a WAV in the assets). */
        @Volatile var referenceClip: () -> FloatArray? = { null }
        /** Whether the owner asked for recordings to be sent ([Diag.attach]). */
        @Volatile var recordings: () -> Boolean = { false }
        /** The phone's audio (for connected earbuds' microphone), and whether to use earbuds when connected. */
        @Volatile var audioManager: () -> AudioManager? = { null }
        @Volatile var headset: () -> Boolean = { true }
        /** The encoder compiled for this phone's NPU ([NpuEncoder]), if there is a build of it. */
        @Volatile var npuEncoder: () -> NpuEncoder? = { null }
        /** What the reference clip says (al-Ikhlāṣ 112:1). */
        private const val REFERENCE_TEXT = "قُلْ هُوَ ٱللَّهُ أَحَدٌ"
        const val ERROR_MODEL = "model"
        const val ERROR_MIC = "mic"
        /** A reading's new words are shown over about this long. */
        private const val REVEAL_MS = 300L


        fun targets(refs: List<AyahRef>, text: (AyahRef) -> String?): List<ReciteTarget> =
            refs.map { ReciteTarget(it, Arabic.words(text(it).orEmpty())) }
    }
}
