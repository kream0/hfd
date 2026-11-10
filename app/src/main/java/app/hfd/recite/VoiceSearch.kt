package app.hfd.recite

import app.hfd.core.quran.QuranSearch
import app.hfd.core.recite.Clarity
import app.hfd.core.recite.Utterance
import app.hfd.diag.Diag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

data class VoiceSearchUi(
    val listening: Boolean = false,
    val loading: Boolean = false,
    /** What was recognised so far. */
    val heard: String = "",
    /** Where it is in the text, best first. */
    val hits: List<QuranSearch.Recited> = emptyList(),
    /** Listening is over (a phrase was found, the reciter stopped, or time ran out). */
    val done: Boolean = false,
    val error: String? = null,
)

/**
 * Finding a passage by reciting some of it: the microphone is cut into utterances as for Recite
 * ([Recorder]), each read by the same recogniser on the phone ([SpeechReader], freely: nothing is
 * expected), and what was read so far is looked for in the whole text ([search]). It stops by
 * itself once a whole phrase has one place that stands out, or after [MAX_SECONDS]; the reciter
 * can stop it sooner. Nothing is recorded or sent.
 */
class VoiceSearch(
    private val scope: CoroutineScope,
    models: () -> ReciteModels,
    private val search: (String) -> List<QuranSearch.Recited>,
) {
    private val recorder = Recorder(ReciteSession.audioManager())
    private val recognizer = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val speech = SpeechReader(models)
    private val _ui = MutableStateFlow(VoiceSearchUi())
    val ui: StateFlow<VoiceSearchUi> = _ui.asStateFlow()
    val level: StateFlow<Float> = recorder.level
    val wave: StateFlow<Wave> = recorder.wave
    private var job: Job? = null

    /** Starts listening (the caller has the microphone permission). */
    fun start() {
        if (job?.isActive == true) return
        _ui.value = VoiceSearchUi(listening = true, loading = !speech.loaded)
        job = scope.launch {
            val reader = withContext(recognizer) { speech.reader() }
            if (reader == null) {
                Diag.log("voice.noModel")
                _ui.value = VoiceSearchUi(done = true, error = ReciteSession.ERROR_MODEL)
                return@launch
            }
            _ui.value = _ui.value.copy(loading = false)
            val inbox = Channel<Utterance>(Channel.UNLIMITED)
            val started = System.currentTimeMillis()
            // Reading goes at its own pace while the microphone goes on: a reading of a phrase
            // still being said is dropped for the next one of it.
            val reading = launch {
                var said = ""
                while (true) {
                    var u = inbox.receiveCatching().getOrNull() ?: break
                    while (!u.final) u = inbox.tryReceive().getOrNull() ?: break
                    val text = withContext(recognizer) { reader(Clarity.prepare(u.pcm), "") }.orEmpty().trim()
                    val all = "$said $text".trim()
                    val hits = if (all.isBlank()) emptyList() else withContext(Dispatchers.Default) { search(all) }
                    if (u.final) said = all
                    // A phrase not found yet doesn't take away what its beginning found.
                    _ui.value = _ui.value.copy(heard = all, hits = hits.ifEmpty { if (u.final) hits else _ui.value.hits })
                    if (u.final) Diag.log("voice.heard", "letters" to all.length, "seconds" to u.seconds, "hits" to hits.take(3).joinToString(",") { it.ref.key })
                    val late = System.currentTimeMillis() - started > MAX_SECONDS * 1000
                    // One place stands out once a whole phrase is said: enough. A phrase the text
                    // says in several places goes on being heard, to tell them apart.
                    val clear = hits.isNotEmpty() && (hits.size == 1 || hits[0].score - hits[1].score >= CLEAR_MARGIN)
                    if ((u.final && clear) || late) recorder.stop()
                }
            }
            try {
                recorder.utterances(null, ReciteSession.headset()).collect { inbox.trySend(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Diag.error("voice.mic", e)
                _ui.value = _ui.value.copy(error = ReciteSession.ERROR_MIC)
            } finally {
                inbox.close()
            }
            reading.join()
            _ui.value = _ui.value.copy(listening = false, done = true)
        }
    }

    /** Stops listening after what is being said has been read. */
    fun stop() = recorder.stop()

    fun close() {
        recorder.stop()
        job?.cancel()
        // Freed on the reading thread, after any reading still going.
        scope.launch(recognizer) { speech.close() }.invokeOnCompletion { recognizer.close() }
    }

    companion object {
        /** A search by reciting listens this long at most. */
        const val MAX_SECONDS = 30
        /** What the best place's share of the phrase must have over the next one's to be the answer. */
        const val CLEAR_MARGIN = 0.04f
    }
}
