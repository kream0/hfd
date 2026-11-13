package app.hfd.recite

import android.media.AudioManager
import app.hfd.BuildConfig
import app.hfd.core.progress.Event
import app.hfd.core.playback.QuranListening
import app.hfd.core.quran.AyahRef
import app.hfd.core.quran.Locator
import app.hfd.core.quran.QuranSearch
import app.hfd.core.ctc.CtcRecognizer
import app.hfd.core.tajweed.Rule
import app.hfd.core.tajweed.TajweedCheck
import app.hfd.core.recite.Arabic
import app.hfd.core.recite.AudioStats
import app.hfd.core.recite.AyahResult
import app.hfd.core.recite.Carry
import app.hfd.core.recite.Clarity
import app.hfd.core.recite.Follower
import app.hfd.core.recite.Level
import app.hfd.core.recite.MuffledWarning
import app.hfd.core.recite.ReciteTarget
import app.hfd.core.recite.Segmenter
import app.hfd.core.recite.Stall
import app.hfd.core.recite.Tracker
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
     * ([ReciteLevel.TAJWEED]): by word (counted from the first āya's first
     * word). [timed]: how many long vowels with a set length were timed so far.
     */
    val tajweed: Map<Int, TajweedCheck.Finding> = emptyMap(),
    val timed: Int = 0,
    /**
     * Words read right in which a letter or a short vowel wasn't heard as written, with the same
     * check: the word (counted as [tajweed]'s) and where in it that character is.
     */
    val unclear: Map<Int, Int> = emptyMap(),
    /** The text recited: a passage's id, or a sūra's ([QuranListening.id]); empty until it is found. */
    val id: String = "",
    /**
     * Nothing was given to recite: what is being said is looked for in the whole Qur'an, and the
     * text is that place's sūra once it is found ([ReciteSession.Anywhere]).
     */
    val locating: Boolean = false,
    /** The places that say what was heard so far, while none stands out: one can be chosen. */
    val candidates: List<QuranSearch.Recited> = emptyList(),
    /** While the place is looked for with a text shown meanwhile (the sūra that was open): the āya of it to show. */
    val view: Int? = null,
    /**
     * The recitation waits at the marked word: what was just said left the text there (a word said
     * differently or passed over), and it goes on once that word is said ([ReciteLevel.FREE]).
     */
    val waiting: Boolean = false,
    /**
     * What is being said isn't the text from the marker but is another place of the Qur'an: the
     * reciter may go there ([ReciteSession.goElsewhere]). The recitation never goes by itself.
     */
    val elsewhere: AyahRef? = null,
) {
    val done: Boolean get() = next == null && !locating
}

/**
 * Reciting [targets] aloud from memory, Tarteel-style: the microphone is cut into utterances
 * ([Recorder]), each recognised on the phone (the CTC model, or whisper.cpp with Tarteel's:
 * [ReciteModels]) again and again as it grows, so the text follows the reciter as they go ([Follower]), word by word on screen. The
 * reciter may go back (start an āya over, say a phrase again) without it counting as a mistake:
 * the āyāt finished are written to the progress log ([record]) with their mistakes and the
 * rating that follows only at the end, as they stand then.
 *
 * With [anywhere], nothing need be given to recite: what is said is looked for in the whole
 * Qur'an as it is heard ([Locator]), and the recitation is followed in that place's sūra from the
 * word it was found at. From there it stays with the text: what isn't the text from the marker
 * moves nothing (the reciter takes it up again there; another place they are clearly reciting is
 * offered, [ReciteUi.elsewhere]), and a sūra finished goes on into the next. How mistakes are
 * dealt with is the reciter's choice ([ReciteLevel]). Main-thread API.
 */
class ReciteSession(
    fadilaId: String,
    targets: List<ReciteTarget>,
    private val scope: CoroutineScope,
    private val models: () -> ReciteModels,
    private val record: (Event) -> Unit,
    private val anywhere: Anywhere? = null,
) {
    /**
     * Reciting anywhere in the Qur'an: the text to look in ([search]), where the reciter is likely
     * to be ([near]: the sūra open; it settles a phrase said in several places), a place to begin
     * at without looking ([start]: "recite from this āya"), a text to show at an āya while the place
     * is looked for ([show]: the page that was open, to read from), and a sūra's id and words
     * ([id], [text]).
     */
    class Anywhere(
        val search: QuranSearch,
        val near: AyahRef?,
        val start: AyahRef?,
        val show: AyahRef?,
        val id: (sura: Int) -> String,
        val text: (sura: Int) -> List<ReciteTarget>?,
    )

    /** A text being recited and how far: the passage given, or the sūra the recitation was found in. */
    private class Text(val id: String, val targets: List<ReciteTarget>) {
        val follower = Follower(targets, skip = Tracker.WAIT_WORDS)
        val tracker = follower.tracker
        /** When each āya was first finished, and those already written to the log. */
        val finishedAt = LinkedHashMap<AyahRef, Long>()
        val recorded = HashSet<AyahRef>()
        /** Words shown as followed so far (flat index): they catch up with the tracker one at a time. */
        var shown = 0
        /**
         * Words right once a whole utterance was read (flat index): they stay so. Saying part of an
         * āya again takes the recitation back there, but what was recited right doesn't go grey again
         * (the owner), nor count as a mistake if a reading of it the second time goes wrong. Not from
         * partial readings: steered toward the text, one may light a word said differently a moment
         * before the whole utterance's reading marks it (the bench's swapped words).
         */
        val wasRight = BooleanArray(tracker.size)
        /** Words right when a whole utterance was read (flat index): shown, they stay right ([wasRight]). */
        val confirmed = BooleanArray(tracker.size)
        /**
         * Words a whole utterance's reading decided (flat index), after its check. Until then a word
         * wrong or missed in a partial reading shows as not yet recited: the model misreads a word a
         * moment before it hears it whole (a madd held: red, then white, the owner, 11 Oct). A
         * mistake shows when the reciter pauses, as the check would have it.
         */
        val decided = BooleanArray(tracker.size)
        /** The stretches read right so far, each beat timed ([TajweedCheck]); a stretch said again replaces the first saying. */
        val stretches = ArrayList<List<TajweedCheck.Timed>>()
        /** Words read right with a character the sound doesn't bear out, and where in the word ([CtcRecognizer.SOUNDS]). */
        val unclear = HashMap<Int, Int>()
        /** The first word followed, when the place was found inside the text. */
        var origin = 0
    }

    private var t = Text(fadilaId, targets)
    val fadilaId: String get() = t.id
    val targets: List<ReciteTarget> get() = t.targets
    /** A text is being followed (always, but before a recitation anywhere has found its place). */
    private var located = targets.isNotEmpty()
    private val placed: Boolean get() = located && t.targets.isNotEmpty()
    /** The āya shown of the text shown while the place is looked for ([Anywhere.show]). */
    private var view: Int? = null
    private val follower: Follower get() = t.follower
    private val tracker get() = t.tracker
    private val finishedAt get() = t.finishedAt
    private val recorded get() = t.recorded
    private var shown: Int
        get() = t.shown
        set(v) { t.shown = v }
    private val wasRight get() = t.wasRight
    private val confirmed get() = t.confirmed
    private val decided get() = t.decided
    private val stretches get() = t.stretches
    /** Looking for the place: before any is found, and the utterance that found it (its later readings are read as its first). */
    private var locator: Locator? = null
    private var foundBy = -1
    /** Looking for where the reciter went, when what they say is no longer the text from the marker. */
    private var astray: Locator? = null
    /** Where they are, when found, and the utterance that said it: offered, not gone to ([goElsewhere]). */
    private var offered: Pair<Locator.Found, Int>? = null
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
    /** The utterance being followed, and where the recitation stood at its first reading ([moved]). */
    private var utterance = -1
    private var utteranceFrom = 0
    /** The word the recitation waits at ([ReciteUi.waiting]); −1: none. */
    private var waitingAt = -1
    /**
     * Whether the place found by what was said ([locate]) has been borne out: the reciter went on
     * from there for [CONFIRM_WORDS] words. Until then it is a guess, and one that isn't followed
     * is left for the place said next ([elsewhere]): a wrong first place must not hold the
     * recitation (the owner, 13 Nov: put in an-Naḥl by the isti'ādha, then held there while
     * reciting al-Baqara). Once borne out, another place is only offered.
     */
    private var borneOut = true
    private var followedSince = 0
    private val startedAt = System.currentTimeMillis()
    private var reveal: Job? = null
    /** The sound of the whole utterance just read, as the recogniser was given it (its check and its timing listen to the same). */
    @Volatile private var prepared: FloatArray? = null
    /** The same before it was brought to a normal level, and what a whole utterance ends on, kept for the next ([Carry]). */
    @Volatile private var heardRaw: FloatArray? = null
    /** The sound of the reading just made (of an utterance whole or still being said), as the recogniser heard it. */
    @Volatile private var heardSound: FloatArray? = null
    private val carry = Carry()

    private val _ui = MutableStateFlow(snapshot(ReciteUi(targets, emptyList(), null)))
    val ui: StateFlow<ReciteUi> = _ui.asStateFlow()
    val level: StateFlow<Float> = recorder.level
    /** Whether what the microphone hears now counts as voice (the button swells with it). */
    val speaking: StateFlow<Boolean> = recorder.speaking
    /** The microphone listened with (the earbuds' or the phone's), while listening. */
    val mic: StateFlow<Mic?> = recorder.mic

    init {
        anywhere?.let { a ->
            if (a.start != null) place(a.start, 0)
            else a.show?.let { at ->
                a.text(at.sura)?.let { words ->
                    t = Text(a.id(at.sura), words)
                    view = words.indexOfFirst { it.ref == at }.coerceAtLeast(0)
                    _ui.value = snapshot(_ui.value)
                }
            }
        }
        // Recognition runs one reading at a time while the recorder goes on; a partial reading
        // left waiting is replaced by the newer one, so the text never lags far behind.
        consumer = scope.launch {
            for (x in wake) {
                while (true) {
                    val u = queue.removeFirstOrNull() ?: break
                    // What the reciter may be saying from where this utterance began: the
                    // recogniser prefers it among what it nearly hears (a dark or faint voice).
                    // (No place yet: nothing is expected, it reads freely.)
                    val expected = if (placed) follower.expected(u.id) else ""
                    var muffled = false
                    var sure: Float? = null
                    val text = withContext(recognizer) {
                        // The phone's speech microphone is faint: at a normal level first (the bench
                        // does the same, Clarity.prepare).
                        // (After what the utterance before ended on, when a pause cut a word: Carry.)
                        val raw = if (placed) carry.join(u.id, u.pcm, tracker.position) else u.pcm
                        val pcm = Clarity.prepare(raw)
                        heardSound = pcm
                        if (u.final) {
                            prepared = pcm
                            heardRaw = raw
                        }
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
                        speech.reader()?.invoke(pcm, expected).also { sure = speech.sure }
                    }
                    if (u.final) _ui.value = _ui.value.copy(pending = (_ui.value.pending - 1).coerceAtLeast(0))
                    // Anywhere in the Qur'an: where this is, first. Found, the utterance is followed
                    // there from the place's first word (this reading and its later ones).
                    var reading = text
                    if (text != null && !placed) reading = locate(u.id, text, u.final) ?: continue
                    else if (text != null && u.id == foundBy) reading = locator?.phrase(text) ?: text
                    val moved = if (reading != null) onHeard(u.id, reading, u.final, check = u.takeIf { it.final }) else 0
                    // The readings stopped moving the text while the voice went on past a pause: the
                    // utterance is ended at that pause, and what was said after it read on its own.
                    if (text != null) stall.heard(u, tracker.position)?.let { sample ->
                        Diag.log("recite.stall", "id" to u.id, "at" to sample / Segmenter.RATE.toFloat(), "seconds" to u.seconds)
                        recorder.splitAt(u.id - idBase, sample)
                    }
                    if (u.final) {
                        // (A voice heard badly: not the room's noise while the reciter thinks, nor a voice read surely.)
                        val symbols = text?.count { !it.isWhitespace() } ?: 0
                        val warn = muffledWarning.heard(muffled, u.seconds, moved, symbols, sure)
                        Diag.log("recite.voice", "id" to u.id, "seconds" to u.seconds, "symbols" to symbols, "sure" to sure, "muffled" to muffled, "moved" to moved, "warn" to warn)
                        if (warn != _ui.value.muffled) _ui.value = _ui.value.copy(muffled = warn)
                        afterWhole(u.id, text, moved)
                    }
                }
            }
        }
    }

    /**
     * A whole utterance is read and followed ([moved] words from where it began). Words heard,
     * and neither the text from the marker nor words before it (the marker would be on those):
     * twice in a row, the reciter is told; and what was said is looked for elsewhere.
     */
    private fun afterWhole(id: Int, text: String?, moved: Int) {
        // (The isti'ādha or the basmala before the text is no stray phrase.)
        val said = text != null && Arabic.skeleton(follower.withoutOpening(text)).length >= STRAY_LETTERS
        if (!borneOut && moved > 0 && id != foundBy) {
            followedSince += moved
            if (followedSince >= CONFIRM_WORDS) borneOut = true
        }
        if (moved != 0 || follower.voice != null || tracker.done) {
            strays = 0
            astray = null
            propose(null)
        } else if (said) {
            // Maybe another place of the Qur'an: offered, or taken while the place is still a guess.
            strays++
            if (text != null) elsewhere(id, text)
        }
        val lost = strays >= STRAYS_LOST
        if (lost != _ui.value.lost) {
            if (lost) Diag.log("recite.lost", "position" to tracker.position)
            _ui.value = _ui.value.copy(lost = lost)
        }
    }

    /**
     * One utterance as the recogniser gives it, reading after reading, the last one whole, and
     * what follows a whole utterance (the screenshot test: no microphone; [onHeard] for one read once).
     */
    fun heardAs(vararg readings: String) {
        val id = ++lastId
        var moved = 0
        var last: String? = null
        for ((i, r) in readings.withIndex()) {
            val final = i == readings.lastIndex
            val reading = if (placed) (if (id == foundBy) locator?.phrase(r) ?: r else r) else locate(id, r, final) ?: continue
            moved = onHeard(id, reading, final)
            last = r
        }
        if (placed) afterWhole(id, last ?: readings.last(), moved)
        reveal?.cancel()
        shown = follower.reach
        _ui.value = snapshot(_ui.value)
    }

    private fun offer(u: Utterance) {
        lastId = maxOf(lastId, u.id)
        if (queue.lastOrNull()?.let { !it.final && it.id == u.id } == true) queue.removeLast()
        queue.addLast(u)
        if (u.final) _ui.value = _ui.value.copy(pending = _ui.value.pending + 1)
        wake.trySend(Unit)
    }

    /**
     * Where what is being said is, in the whole Qur'an ([Locator]). Found: the text is that place's
     * sūra, and what was heard from the place's first word is returned for the follower. Null
     * until then (the screen shows what was heard, and the places that say it when there are several).
     */
    private fun locate(id: Int, text: String, final: Boolean): String? {
        val a = anywhere ?: return null
        val l = locator ?: Locator(a.search, a.near).also { locator = it }
        val found = l.heard(text, final)
        if (final || found != null) Diag.log(
            "recite.locate", "id" to id, "final" to final, "text" to text, "found" to found?.ref?.key, "word" to found?.word, "score" to found?.score,
            "places" to l.candidates.take(4).joinToString(",") { it.ref.key },
        )
        if (found == null) {
            _ui.value = _ui.value.copy(heard = text.ifBlank { null } ?: _ui.value.heard, candidates = l.candidates.take(MAX_PLACES))
            return null
        }
        place(found.ref, found.word)
        foundBy = id
        borneOut = false
        followedSince = 0
        return found.phrase
    }

    /**
     * A whole utterance that followed nothing of the text from the marker: looked for in the
     * whole Qur'an (it needs more letters than a first place does, and where the reciter is
     * settles a phrase said in several places). Found, the place is offered ([ReciteUi.elsewhere]):
     * the recitation stays where it is until the reciter asks (the owner, 13 Nov: it went there by
     * itself, the text scrolling away and back).
     */
    private fun elsewhere(id: Int, text: String) {
        val a = anywhere ?: return
        if (!placed) return
        val here = targets[tracker.locate(tracker.position.coerceAtMost(tracker.size - 1)).first].ref
        // (A place that was only a guess says nothing of where the reciter is likely to be.)
        val l = astray ?: Locator(a.search, if (borneOut) here else a.near, Locator.MIN_LETTERS_ELSEWHERE).also { astray = it }
        val found = l.heard(text, final = true)
        Diag.log("recite.elsewhere", "id" to id, "text" to text, "from" to here.key, "found" to found?.ref?.key, "word" to found?.word, "score" to found?.score)
        found ?: return
        astray = null
        // (Words of the text around the marker, said with a mistake or out of order, are not
        // elsewhere: the reciter is told to take it up again at the marker, no more.)
        if (a.id(found.ref.sura) == t.id) {
            val at = targets.indexOfFirst { it.ref == found.ref }.takeIf { it >= 0 }?.let { tracker.startOf(it) + found.word }
            if (at != null && at in tracker.position - NEAR_BEFORE..tracker.position + NEAR_AFTER) return
        }
        propose(found to id)
        // (The place followed until now was only a guess: this one is taken in its stead.)
        if (!borneOut) {
            goElsewhere()
            strays = 0
            if (_ui.value.lost) _ui.value = _ui.value.copy(lost = false)
        }
    }

    private fun propose(place: Pair<Locator.Found, Int>?) {
        offered = place
        if (_ui.value.elsewhere != place?.first?.ref) _ui.value = _ui.value.copy(elsewhere = place?.first?.ref)
    }

    /** The reciter goes to the place offered ([ReciteUi.elsewhere]): the recitation is followed from what was said there. */
    fun goElsewhere() {
        val (found, id) = offered ?: return
        propose(null)
        Diag.log("recite.moved", "to" to found.ref.key, "word" to found.word, "asked" to borneOut)
        // (Asked for: the reciter knows where they are. Else it is another guess.)
        followedSince = 0
        // (In the same sūra the words said are marked as they were heard; in another, that
        // sūra's follower takes the utterance as its first.)
        if (place(found.ref, found.word)) tracker.feed(found.phrase) else follower.heard(id, found.phrase, true)
        afterProgress(null, final = true)
    }

    /**
     * The recitation is at word [word] of āya [ref]: in its sūra's text, which replaces the one
     * followed until now if it is another. True if the text stays the same.
     */
    private fun place(ref: AyahRef, word: Int): Boolean {
        val a = anywhere ?: return false
        // (The text shown while looking may be the one: it is then followed, from here.)
        val there = t.targets.isNotEmpty() && t.id == a.id(ref.sura)
        val same = there && located
        if (!there) {
            val words = a.text(ref.sura) ?: return false
            recordFinished()
            t = Text(a.id(ref.sura), words)
        }
        located = true
        view = null
        reveal?.cancel()
        val aya = targets.indexOfFirst { it.ref == ref }.coerceAtLeast(0)
        val at = (tracker.startOf(aya) + word).coerceIn(0, (tracker.size - 1).coerceAtLeast(0))
        tracker.startAt(at)
        shown = at
        t.origin = if (same) minOf(t.origin, at) else at
        strays = 0
        offered = null
        waitingAt = -1
        Diag.log("recite.placed", "ref" to ref.key, "word" to word, "sura" to t.id, "same" to same)
        _ui.value = snapshot(
            if (same) _ui.value.copy(candidates = emptyList(), lost = false, waiting = false, elsewhere = null)
            else _ui.value.copy(candidates = emptyList(), lost = false, waiting = false, elsewhere = null, results = emptyList(), tajweed = emptyMap(), timed = 0, unclear = emptyMap()),
        )
        return same
    }

    /** One of the places listed while none stood out ([ReciteUi.candidates]), chosen by the reciter. */
    fun choose(hit: QuranSearch.Recited) {
        place(hit.ref, hit.word)
        borneOut = true
        locator = null
    }

    /** Listening to a voice at [level], shown without a microphone (the screenshot test). */
    fun previewListening(level: Float, waiting: Boolean = false, muffled: Boolean = false) {
        recorder.preview(level, speaking = level > 0f)
        _ui.value = _ui.value.copy(listening = true, waiting = waiting, muffled = muffled)
    }

    /** What was heard and the places that say it, shown as while looking for one (the screenshot test). */
    fun previewLocating(heard: String, places: List<QuranSearch.Recited>) {
        _ui.value = _ui.value.copy(heard = heard, candidates = places, listening = true)
    }

    /** Starts listening (the caller has the microphone permission). */
    fun start() {
        if (listenJob?.isActive == true || (placed && tracker.done)) return
        val m = models()
        Diag.log(
            "recite.start", "fadila" to fadilaId, "ayat" to targets.size, "from" to targets.firstOrNull()?.ref?.key, "anywhere" to (anywhere != null),
            "model" to m.whisper?.length(), "ctc" to m.ctc?.length(), "done" to tracker.done,
        )
        if (m.whisper == null && m.ctc == null) return
        carry.clear()
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
                    // What was recited: the passage given; of a sūra found by what was said, from
                    // the āya it was found at to the one reached.
                    val first = if (anywhere == null) targets.firstOrNull()?.ref else targets.getOrNull(tracker.locate(t.origin).first)?.ref
                    val last = if (anywhere == null) targets.lastOrNull()?.ref else targets.getOrNull(tracker.locate((tracker.position - 1).coerceAtLeast(t.origin)).first)?.ref
                    Diag.log("recite.session", "fadila" to fadilaId, "from" to first?.key, "to" to last?.key, "seconds" to pcm.size / 16_000f)
                    // The name says what was recited and when: session-<passage>-<from>-<to>-<HHmmss>,
                    // e.g. session-imran-opening-3_1-3_9-213243 (several in a run don't overwrite each other).
                    val range = listOfNotNull(first, last).joinToString("-") { "${it.sura}_${it.aya}" }
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
        if (!placed) return
        follower.hint(through = lastId) ?: return
        recorder.restart()
        carry.clear()
        val dropped = queue.count { it.id <= lastId && it.final }
        queue.removeAll { it.id <= lastId }
        _ui.value = _ui.value.copy(pending = (_ui.value.pending - dropped).coerceAtLeast(0))
        strays = 0
        waitingAt = -1
        propose(null)
        _ui.value = _ui.value.copy(lost = false, waiting = false)
        // The hinted word shows at once.
        shown = maxOf(shown, tracker.position)
        afterProgress(null, final = true)
    }

    /** A whole utterance heard, shown at once (the screenshot test): looked for first, when no place is found yet. */
    fun onHeard(text: String) {
        val id = ++lastId
        onHeard(id, if (placed) text else locate(id, text, final = true) ?: return, final = true)
        reveal?.cancel()
        shown = tracker.position
        _ui.value = snapshot(_ui.value)
    }

    /** A reading of utterance [id]: partial ones move the text on, the final one stays. */
    private fun onHeard(id: Int, text: String, final: Boolean): Int {
        follow(id, text, final)
        settle(id, final)
        afterProgress(text.ifBlank { null }, final)
        return moved(id)
    }

    /**
     * A whole utterance is read (and checked): reciting freely, the recitation goes back to the
     * first word it left wrong or missed and waits there ([Follower.settle]).
     */
    private fun settle(id: Int, final: Boolean) {
        val at = if (final) follower.settle(id) else null
        if (at != null) Diag.log("recite.wait", "id" to id, "word" to at, "ref" to targets[tracker.locate(at).first].ref.key, "edge" to follower.atEdge)
        // Waiting until the recitation moves again (on, or back). (Not for the words an utterance
        // ends on: a pause inside a phrase; they are only not said yet.)
        if (at != null && !follower.atEdge) waitingAt = at else if (tracker.position != waitingAt) waitingAt = -1
        val waiting = waitingAt >= 0
        if (waiting != _ui.value.waiting) _ui.value = _ui.value.copy(waiting = waiting)
    }

    /**
     * How far utterance [id] has taken the recitation, from where its first reading found it
     * (not from its reading before: by the whole one, the partial ones have said nearly all of it,
     * and 2.9.0 counted every utterance as having followed nothing: "I've lost you" while the
     * text followed, the owner's session of 13 Nov).
     */
    private fun moved(id: Int): Int = if (id == utterance) tracker.position - utteranceFrom else 0

    /**
     * The same, from the recogniser: the mistakes a whole utterance ([check]) leaves are checked
     * first, by reading it again held to the text it covered (Follower.Check), before they show
     * or count. The phone's model misreads words said quickly or darkly (الحي القيوم read حيظهم,
     * 10 Oct): most of the owner's mistakes were the model's.
     */
    private suspend fun onHeard(id: Int, text: String, final: Boolean, check: Utterance?): Int {
        val level = level()
        // Still being said, and the recogniser can hold a text to the sound at once (the CTC one):
        // a word not right so far is checked now, not when the reciter pauses, so that what was
        // said after it doesn't wait for the pause to show (2.10.0: seconds late in a long phrase).
        val now = if (!final && speech.timing) heardSound else null
        follow(id, text, final, hold = now == null)
        if (now != null) {
            follower.toCheck(id, partial = true)?.let { c ->
                val again = withContext(recognizer) { speech.reader()?.invoke(now, c.expected) }
                val costs = again?.let(Follower.Check::costs)
                if (costs != null) {
                    val right = follower.checked(c, costs)
                    Diag.log("recite.check", "id" to id, "partial" to true, "words" to c.words.joinToString(","), "right" to right, "costs" to again.substringAfterLast('\t'))
                }
            }
            follower.hold(id)
        }
        val sound = if (check != null) prepared ?: Clarity.prepare(check.pcm) else null
        if (check != null && sound != null) follower.toCheck(id)?.let { c ->
            val again = withContext(recognizer) { speech.reader()?.invoke(sound, c.expected) }
            val costs = again?.let(Follower.Check::costs)
            if (costs != null) {
                val right = follower.checked(c, costs)
                Diag.log("recite.check", "id" to id, "level" to level.name, "words" to c.words.joinToString(","), "right" to right, "reading" to again)
            }
        }
        // (Timing is an extra: whatever goes wrong in it, the recitation goes on.)
        // (Not the utterance a place was found by: what was said before it may be read with it.)
        if (sound != null && id != foundBy && level == ReciteLevel.TAJWEED && speech.timing) follower.read(id)?.let { read ->
            try {
                time(id, read, sound)
                sounds(id, read, sound)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Diag.log("recite.tajweedError", "id" to id, "error" to (e.message ?: e.javaClass.simpleName))
            }
        }
        settle(id, final)
        if (final) carryOn(id, sound)
        afterProgress(text.ifBlank { null }, final)
        return moved(id)
    }

    /**
     * What whole utterance [id] ends on after the words it said of the text is kept, to be heard
     * with the next utterance ([Carry]): a pause inside a word cuts it in two, and neither
     * utterance says it. The recogniser says where each character it wrote lies in the sound
     * (the one that does: the CTC model).
     */
    private suspend fun carryOn(id: Int, sound: FloatArray?) {
        val raw = heardRaw
        val said = if (sound != null && raw != null && raw.size == sound.size && speech.timing && id != foundBy) follower.edge(id) else null
        if (said == null || raw == null) return carry.clear()
        val written = try {
            withContext(recognizer) { speech.reader()?.invoke(sound!!, CtcRecognizer.WRITTEN) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val times = written?.let(CtcRecognizer::times) ?: return carry.clear()
        carry.keep(raw, written.substringBefore('\t'), times.second, times.first, said, tracker.position)
        if (carry.seconds > 0f) Diag.log("recite.carry", "id" to id, "seconds" to carry.seconds, "word" to tracker.position)
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

    /**
     * The words of a stretch read right, held to its sound letter by letter: one whose worst
     * character costs more than [CtcRecognizer.UNCLEAR] has a letter or a short vowel that wasn't
     * said as written (a stretch said again replaces what its first saying found).
     */
    private suspend fun sounds(id: Int, read: Follower.Read, sound: FloatArray) {
        val reading = withContext(recognizer) { speech.reader()?.invoke(sound, CtcRecognizer.SOUNDS + read.text) } ?: return
        val found = CtcRecognizer.sounds(reading) ?: return
        val opening = read.opening.size
        if (found.size != opening + read.to - read.from) return
        for (w in read.from until read.to) {
            val (cost, at) = found[opening + w - read.from]
            // (The disconnected letters are held notes the model doesn't write as they are written.)
            val (a, word) = tracker.locate(w)
            val letters = word == 0 && Tracker.isLetters(targets[a].ref)
            if (cost > CtcRecognizer.UNCLEAR && at >= 0 && !letters) t.unclear[w] = at else t.unclear.remove(w)
        }
        _ui.value = _ui.value.copy(unclear = t.unclear.toMap())
        Diag.log(
            "recite.sounds", "id" to id, "from" to read.from, "to" to read.to,
            "worst" to (found.maxOfOrNull { it.first } ?: 0f),
            "found" to (read.from until read.to).filter { it in t.unclear }.joinToString(",") { "$it:${t.unclear[it]}:${"%.1f".format(java.util.Locale.US, found[opening + it - read.from].first)}" },
        )
    }

    /** Long vowels shown as not held, and words as not said as written (the screenshot test). */
    fun previewTajweed(found: List<TajweedCheck.Finding>, timed: Int, unclear: Map<Int, Int> = emptyMap()) {
        _ui.value = _ui.value.copy(tajweed = found.associateBy { it.word }, timed = timed, unclear = unclear)
    }

    private fun follow(id: Int, text: String, final: Boolean, hold: Boolean = true): Int {
        val before = tracker.position
        if (id != utterance) {
            utterance = id
            utteranceFrom = before
        }
        // With the recogniser that writes what it hears, at every level: letter for letter, and the
        // recitation waits at a word not right (never past one: the owner, 13 Nov). Not on Whisper's
        // readings, too rough for that: its mistakes are marked (or just not lit, free) and it goes on.
        follower.waits = !speech.rough
        tracker.strict = !speech.rough
        val moved = follower.heard(id, text, final, hold)
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
        // (A sūra finished while reciting anywhere goes on into the next one.)
        val next = if (final && tracker.done && anywhere != null) QuranListening.suraOf(t.id)?.let { it + 1 }?.takeIf { it <= SURAS } else null
        if (final) {
            val now = System.currentTimeMillis()
            for (r in finished) finishedAt.putIfAbsent(r.ref, now)
            if (tracker.done) {
                recordFinished()
                if (next == null) recorder.stop()
            }
        }
        _ui.value = _ui.value.copy(heard = heard ?: _ui.value.heard, results = finished.map(::withoutRight))
        if (next != null) place(AyahRef(next, 1), 0) else catchUp()
    }

    /**
     * The words shown follow the tracker one at a time, like a reading pointer (a reading often
     * brings several at once); going back shows at once.
     */
    private fun catchUp() {
        // Taken up again further on, among words already recited: those between were shown before, and are at once.
        tracker.resumed?.let { if (it > shown && it <= follower.reach) shown = it }
        if (follower.reach <= shown) {
            shown = follower.reach
            _ui.value = snapshot(_ui.value)
            return
        }
        _ui.value = snapshot(_ui.value)
        if (reveal?.isActive == true) return
        reveal = scope.launch {
            // (No further than a word the recitation may have to wait at: Follower.reach.)
            while (shown < follower.reach) {
                val behind = follower.reach - shown
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

    /**
     * [r] without the mistakes on words that were right once ([wasRight]), and with the words the
     * recitation waited at ([Follower.stumbled]): said right in the end, but not at first.
     */
    private fun withoutRight(r: AyahResult): AyahResult {
        val start = tracker.startOf(targets.indexOfFirst { it.ref == r.ref })
        return r.copy(mistakes = (0 until r.words).filter { (it in r.mistakes && !wasRight[start + it]) || follower.stumbled[start + it] })
    }

    private fun snapshot(base: ReciteUi): ReciteUi {
        var flat = 0
        // (Reciting freely nothing is marked, whatever a reading of words said again makes of them.)
        val free = level() == ReciteLevel.FREE
        val status = targets.indices.map { a ->
            targets[a].words.indices.map { w ->
                val raw = if (flat + w < shown) tracker.statusOf(a, w) else WordStatus.PENDING
                val undecided = (raw == WordStatus.WRONG || raw == WordStatus.MISSED) && (free || !decided[flat + w])
                val st = if (undecided) WordStatus.PENDING else raw
                if (st == WordStatus.OK && confirmed[flat + w]) wasRight[flat + w] = true
                when {
                    // Marking mistakes: the word the recitation waited at is one, and stays marked
                    // once said right (it is what there is to work on).
                    !free && follower.stumbled[flat + w] && flat + w <= tracker.position -> WordStatus.WRONG
                    wasRight[flat + w] -> WordStatus.OK
                    else -> st
                }
            }.also { flat += targets[a].words.size }
        }
        // The marker is on the word being said (Follower.voice), else the next one; never past the
        // words shown so far (they come one at a time).
        val next = follower.voice?.let { minOf(it, shown) } ?: shown
        if (!placed) return base.copy(targets = targets, status = status, next = null, id = t.id, locating = true, view = view)
        return base.copy(targets = targets, status = status, next = if (next >= tracker.size) null else tracker.locate(next), id = t.id, locating = false, view = null)
    }

    companion object {
        /** The reference clip the models are tried on ([SpeechReader]), set by the app (a WAV in the assets). */
        @Volatile var referenceClip: () -> FloatArray? = { null }
        /** How mistakes are dealt with ([app.hfd.data.AppSettings.reciteLevel]). */
        @Volatile var level: () -> ReciteLevel = { ReciteLevel.FREE }
        /** Whether the owner asked for recordings to be sent ([Diag.attach]). */
        @Volatile var recordings: () -> Boolean = { false }
        /** Whether Recite's screen listens as soon as it opens (not in the screenshot test, which has no microphone). */
        @Volatile var listensAtOnce = true
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
        /** Words the reciter goes on for from a place found, for it to be theirs ([borneOut]). */
        private const val CONFIRM_WORDS = 4
        /** Words before and after the marker that are not another place to offer ([elsewhere]). */
        private const val NEAR_BEFORE = 20
        private const val NEAR_AFTER = 40
        /** Places listed while none stands out, at most. */
        private const val MAX_PLACES = 5
        private const val SURAS = 114


        fun targets(refs: List<AyahRef>, text: (AyahRef) -> String?): List<ReciteTarget> =
            refs.map { ReciteTarget(it, Arabic.words(text(it).orEmpty())) }
    }
}
