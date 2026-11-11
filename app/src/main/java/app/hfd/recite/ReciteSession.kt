package app.hfd.recite

import android.media.AudioManager
import app.hfd.BuildConfig
import app.hfd.core.progress.Event
import app.hfd.core.quran.AyahRef
import app.hfd.core.ctc.CtcRecognizer
import app.hfd.core.tajweed.Rule
import app.hfd.core.tajweed.TajweedCheck
import app.hfd.core.recite.Arabic
import app.hfd.core.recite.AudioStats
import app.hfd.core.recite.AyahResult
import app.hfd.core.recite.Clarity
import app.hfd.core.recite.Follower
import app.hfd.core.recite.Level
import app.hfd.core.recite.MuffledWarning
import app.hfd.core.recite.ReciteTarget
import app.hfd.core.recite.Segmenter
import app.hfd.core.recite.Stall
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
    /**
     * What is being said is no longer followed: whole phrases in a row heard, none of them the
     * text from the marker nor words before it. The screen says where to take it up again.
     */
    val lost: Boolean = false,
    /**
     * The long vowels not held as their rule asks, when the reciter asked for that check
     * ([app.hfd.data.AppSettings.reciteTajweed]): by word (counted from the first āya's first
     * word). [timed]: how many long vowels with a set length were timed so far.
     */
    val tajweed: Map<Int, TajweedCheck.Finding> = emptyMap(),
    val timed: Int = 0,
) {
    val done: Boolean get() = next == null
}

/**
 * Reciting [targets] aloud from memory, Tarteel-style: the microphone is cut into utterances
 * ([Recorder]), each recognised on the phone (the CTC model, or whisper.cpp with Tarteel's:
 * [ReciteModels]) again and again as it grows, so the text follows the reciter as they go ([Follower]), word by word on screen. The
 * reciter may go back (start an āya over, say a phrase again) without it counting as a mistake:
 * the āyāt finished are written to the progress log ([record]) with their mistakes and the
 * rating that follows only at the end, as they stand then. Main-thread API.
 */
class ReciteSession(
    val fadilaId: String,
    val targets: List<ReciteTarget>,
    private val scope: CoroutineScope,
    private val models: () -> ReciteModels,
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
    /** What the recorder's own numbering of utterances starts from, this time it listens. */
    private var idBase = 0
    private val stall = Stall()
    private val speech = SpeechReader(models)
    private var listenJob: Job? = null
    private val consumer: Job
    private var heardChunks = 0
    private val muffledWarning = MuffledWarning()
    /** Whole utterances in a row that said something and followed nothing ([ReciteUi.lost]). */
    private var strays = 0
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
    /**
     * Words a whole utterance's reading decided (flat index), after its check. Until then a word
     * wrong or missed in a partial reading shows as not yet recited: the model misreads a word a
     * moment before it hears it whole (a madd held: red, then white, the owner, 11 Oct). A
     * mistake shows when the reciter pauses, as the check would have it.
     */
    private val decided = BooleanArray(tracker.size)
    /** The sound of the whole utterance just read, as the recogniser was given it (its check and its timing listen to the same). */
    @Volatile private var prepared: FloatArray? = null
    /** The stretches read right so far, each beat timed ([TajweedCheck]); a stretch said again replaces the first saying. */
    private val stretches = ArrayList<List<TajweedCheck.Timed>>()

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
                        if (u.final) prepared = pcm
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
                        speech.reader()?.invoke(pcm, expected)
                    }
                    if (u.final) _ui.value = _ui.value.copy(pending = (_ui.value.pending - 1).coerceAtLeast(0))
                    val moved = if (text != null) onHeard(u.id, text, u.final, check = u.takeIf { it.final }) else 0
                    // The readings stopped moving the text while the voice went on past a pause: the
                    // utterance is ended at that pause, and what was said after it read on its own.
                    if (text != null) stall.heard(u, tracker.position)?.let { sample ->
                        Diag.log("recite.stall", "id" to u.id, "at" to sample / Segmenter.RATE.toFloat(), "seconds" to u.seconds)
                        recorder.splitAt(u.id - idBase, sample)
                    }
                    if (u.final) {
                        val warn = muffledWarning.heard(muffled, u.pcm.size / Segmenter.RATE.toFloat(), moved)
                        if (warn != _ui.value.muffled) _ui.value = _ui.value.copy(muffled = warn)
                        // Words heard, and neither the text from the marker nor words before it
                        // (the marker would be on those): twice in a row, the reciter is told.
                        val said = text != null && Arabic.skeleton(text).length >= STRAY_LETTERS
                        strays = if (moved != 0 || follower.voice != null || tracker.done) 0 else if (said) strays + 1 else strays
                        val lost = strays >= STRAYS_LOST
                        if (lost != _ui.value.lost) {
                            if (lost) Diag.log("recite.lost", "position" to tracker.position)
                            _ui.value = _ui.value.copy(lost = lost)
                        }
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
        val m = models()
        Diag.log(
            "recite.start", "fadila" to fadilaId, "ayat" to targets.size, "from" to targets.firstOrNull()?.ref?.key,
            "model" to m.whisper?.length(), "ctc" to m.ctc?.length(), "done" to tracker.done,
        )
        if (m.whisper == null && m.ctc == null) return
        _ui.value = _ui.value.copy(listening = true, error = null, loading = !speech.loaded)
        // Load the model now rather than on the first chunk.
        scope.launch {
            val w = withContext(recognizer) { speech.reader() }
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
                    // (A sūra's id, "sura:18", without its colon: a file's name.)
                    Diag.attach("session-${fadilaId.replace(":", "")}-$range-$time", pcm, true)
                } else null
                // The recorder counts utterances from 0 each time it starts: they go on after this
                // session's (the follower ignores ids it is done with).
                val base = lastId + 1
                idBase = base
                recorder.utterances(capture, headset()).collect { offer(Utterance(base + it.id, it.pcm, it.final, it.pauses)) }
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
        strays = 0
        _ui.value = _ui.value.copy(lost = false)
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
        val sound = if (check != null) prepared ?: Clarity.prepare(check.pcm) else null
        if (check != null && sound != null) follower.toCheck(id)?.let { c ->
            val again = withContext(recognizer) { speech.reader()?.invoke(sound, c.expected) }
            val costs = again?.let(Follower.Check::costs)
            if (costs != null) {
                val right = follower.checked(c, costs)
                Diag.log("recite.check", "id" to id, "words" to c.words.joinToString(","), "right" to right, "reading" to again)
            }
        }
        // (Timing is an extra: whatever goes wrong in it, the recitation goes on.)
        if (sound != null && tajweed() && speech.timing) follower.read(id)?.let { read ->
            try {
                time(id, read, sound)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Diag.log("recite.tajweedError", "id" to id, "error" to (e.message ?: e.javaClass.simpleName))
            }
        }
        afterProgress(text.ifBlank { null }, final)
        return moved
    }

    /**
     * The long vowels of a stretch read right, timed on its sound: the recogniser says where each
     * letter lies, and what is held from a consonant to the next is compared with the recitation's
     * own natural madd ([TajweedCheck]).
     */
    private suspend fun time(id: Int, read: Follower.Read, sound: FloatArray) {
        val beats = TajweedCheck.stretch(targets.map { it.words }, read.from, read.to, read.opening)
        if (beats.isEmpty()) return
        val reading = withContext(recognizer) { speech.reader()?.invoke(sound, CtcRecognizer.TIMES + read.text) } ?: return
        val (frames, placed) = CtcRecognizer.times(reading) ?: return
        val now = TajweedCheck.time(beats, placed, TajweedCheck.pauses(sound, frames))
        if (now.isEmpty()) return
        stretches.removeAll { old -> old.first().beat.word < read.to && old.last().beat.word >= read.from }
        stretches += now
        val natural = TajweedCheck.natural(stretches.flatten())
        val found = stretches.flatMap { TajweedCheck.check(it, natural) }
        val held = stretches.sumOf { s -> s.count { it.beat.madd == Rule.MADD_MUTTASIL || it.beat.madd == Rule.MADD_LAZIM || (it.beat.madd == Rule.MADD_MUNFASIL && !it.paused && !it.last) } }
        _ui.value = _ui.value.copy(tajweed = found.associateBy { it.word }, timed = held)
        Diag.log(
            "recite.tajweed", "id" to id, "from" to read.from, "to" to read.to, "beats" to now.size, "natural" to (natural ?: 0f), "held" to held,
            "found" to found.joinToString(",") { "${it.word}:${it.rule.name}:${it.fault.name}:${"%.2f".format(java.util.Locale.US, it.seconds)}" },
        )
    }

    /** Long vowels shown as not held (the screenshot test). */
    fun previewTajweed(found: List<TajweedCheck.Finding>, timed: Int) {
        _ui.value = _ui.value.copy(tajweed = found.associateBy { it.word }, timed = timed)
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
            speech.close()
        }.invokeOnCompletion { recognizer.close() }
    }

    /** After a reading (or a hint): the text follows; at the end of the passage, the āyāt are recorded. */
    private fun afterProgress(heard: String?, final: Boolean) {
        if (final) for (w in 0 until tracker.position) if (tracker.status[w] == WordStatus.OK) confirmed[w] = true
        for (w in tracker.position until tracker.size) decided[w] = false
        if (final) for (w in 0 until tracker.position) decided[w] = true
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
                val raw = if (flat + w < shown) tracker.statusOf(a, w) else WordStatus.PENDING
                val undecided = (raw == WordStatus.WRONG || raw == WordStatus.MISSED) && !decided[flat + w]
                val st = if (undecided) WordStatus.PENDING else raw
                if (st == WordStatus.OK && confirmed[flat + w]) wasRight[flat + w] = true
                if (wasRight[flat + w]) WordStatus.OK else st
            }.also { flat += targets[a].words.size }
        }
        // The marker is on the word being said (Follower.voice), else the next one; never past the
        // words shown so far (they come one at a time).
        val next = follower.voice?.let { minOf(it, shown) } ?: shown
        return base.copy(status = status, next = if (next >= tracker.size) null else tracker.locate(next))
    }

    companion object {
        /** The reference clip the models are tried on ([SpeechReader]), set by the app (a WAV in the assets). */
        @Volatile var referenceClip: () -> FloatArray? = { null }
        /** Whether the reciter asked for the long vowels to be timed ([app.hfd.data.AppSettings.reciteTajweed]). */
        @Volatile var tajweed: () -> Boolean = { false }
        /** Whether the owner asked for recordings to be sent ([Diag.attach]). */
        @Volatile var recordings: () -> Boolean = { false }
        /** The phone's audio (for connected earbuds' microphone), and whether to use earbuds when connected. */
        @Volatile var audioManager: () -> AudioManager? = { null }
        @Volatile var headset: () -> Boolean = { true }
        /** The encoder compiled for this phone's NPU ([NpuEncoder]), if there is a build of it. */
        @Volatile var npuEncoder: () -> NpuEncoder? = { null }
        const val ERROR_MODEL = "model"
        const val ERROR_MIC = "mic"
        /** A reading's new words are shown over about this long. */
        private const val REVEAL_MS = 300L
        /** Letters an utterance must say to count as a phrase that followed nothing, and how many such in a row. */
        private const val STRAY_LETTERS = 6
        private const val STRAYS_LOST = 2


        fun targets(refs: List<AyahRef>, text: (AyahRef) -> String?): List<ReciteTarget> =
            refs.map { ReciteTarget(it, Arabic.words(text(it).orEmpty())) }
    }
}
