package app.hfd

import android.app.Application
import android.media.AudioManager
import androidx.annotation.StringRes
import app.hfd.core.playback.GapMode
import app.hfd.core.playback.QuranListening
import app.hfd.core.quran.AyahRef
import app.hfd.data.ContentRepo
import app.hfd.data.Favorites
import app.hfd.diag.Diag
import app.hfd.data.ProgressRepo
import app.hfd.data.SessionStore
import app.hfd.data.RangeStore
import app.hfd.data.ReadingPlace
import app.hfd.data.Settings
import app.hfd.download.AudioDownloader
import app.hfd.download.AudioStore
import app.hfd.playback.NowPlaying
import app.hfd.playback.PlaybackEngine
import app.hfd.playback.PlaySession
import app.hfd.playback.TimingsRepo
import app.hfd.playback.WordsRepo
import app.hfd.playback.PlayerConnection
import app.hfd.recite.ModelState
import app.hfd.recite.ModelStore
import app.hfd.recite.ReciteEngine
import app.hfd.recite.ReciteModels
import app.hfd.recite.SpeechModel
import app.hfd.recite.NpuEncoder
import app.hfd.recite.ReciteSession
import app.hfd.recite.Whisper
import app.hfd.update.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Minimal service locator: the app is small enough not to need a DI framework. */
object Graph {
    lateinit var app: Application
        private set

    /** Process-wide scope; state bookkeeping runs on Main, IO work switches dispatchers. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    val settings: Settings by lazy { Settings(app) }
    val updater: Updater by lazy { Updater(app, http, settings, scope) }
    val content: ContentRepo by lazy { ContentRepo(app, scope) { settings.current.hiddenFadail } }
    val audio: AudioStore by lazy { AudioStore(app, scope, timings) }
    val timings: TimingsRepo by lazy { TimingsRepo(app) }
    val words: WordsRepo by lazy { WordsRepo(app) }
    val downloads: AudioDownloader by lazy { AudioDownloader(app, audio, http, scope, timings) }
    val player: PlayerConnection by lazy { PlayerConnection(app) }
    val ranges: RangeStore by lazy { RangeStore(app) }
    /** Where the Qur'an was last being read: the one place a sūra reopens at and listening starts from. */
    val reading: ReadingPlace by lazy { ReadingPlace(app, scope) }
    /** Āyāt marked while reading the Qur'an section. */
    val favorites: Favorites by lazy { Favorites(app) }
    val progress: ProgressRepo by lazy { ProgressRepo(app, scope) }
    val sessions: SessionStore by lazy { SessionStore(app) }
    /** Speech model for the Recite mode (downloaded once). */
    val speech: ModelStore by lazy { ModelStore(app, http, scope) }
    /** The CTC recogniser's model (2.2.0), preferred once it is on the phone. */
    val speechCtc: ModelStore by lazy { ModelStore(app, http, scope, SpeechModel.CTC) }

    /** The models a Recite session may read with, as the settings and the downloads stand. */
    fun reciteModels(): ReciteModels = ReciteModels(
        ctc = (speechCtc.state.value as? ModelState.Ready)?.file?.takeIf { settings.current.reciteEngine == ReciteEngine.BEST },
        whisper = (speech.state.value as? ModelState.Ready)?.file,
    )
    /** Recite's encoder compiled for this phone's NPU, if there is a build for it. */
    val npu: NpuEncoder by lazy { NpuEncoder(app, http) }

    private val _recite = MutableStateFlow<ReciteSession?>(null)
    /** The recitation in progress (Recite mode), if any. */
    val recite: StateFlow<ReciteSession?> = _recite.asStateFlow()

    /**
     * Starts reciting [refs] aloud: of passage [fadilaId], or of a sūra of the Qur'an section (its
     * id there, [QuranListening.id]). Replaces any recitation in progress. A passage's āyāt go to
     * the progress log with their mistakes; a sūra's don't: the Qur'an section is outside what is
     * being learnt (as listening there is).
     */
    fun openRecite(fadilaId: String, refs: List<AyahRef>) {
        closeRecite()
        val c = content.content.value ?: return
        // What plays would be heard by the microphone.
        if (player.state.value.playWhenReady) player.pause()
        val learnt = QuranListening.suraOf(fadilaId) == null
        _recite.value = ReciteSession(
            fadilaId,
            ReciteSession.targets(refs) { c.quran.text(it) },
            scope,
            models = ::reciteModels,
            record = { if (learnt) progress.record(it) },
        )
    }

    /**
     * Starts reciting anywhere in the Qur'an: what is said is looked for in the whole text and
     * followed in its sūra from where it was found ([ReciteSession.Anywhere]). [near]: where the
     * reciter is likely to be (the sūra open, the place last read); [show]: its page is shown
     * from there while the place is looked for (it was open: one may be reading from it).
     * [start]: an āya to begin at without looking ("recite from here"). Nothing goes to the
     * progress log: the Qur'an section is outside what is being learnt.
     */
    fun reciteAnywhere(near: AyahRef? = null, start: AyahRef? = null, show: Boolean = false) {
        closeRecite()
        val c = content.content.value ?: return
        if (player.state.value.playWhenReady) player.pause()
        _recite.value = ReciteSession(
            "",
            emptyList(),
            scope,
            models = ::reciteModels,
            record = {},
            anywhere = ReciteSession.Anywhere(
                c.search, near ?: start, start, near.takeIf { show },
                id = QuranListening::id,
                text = { n -> c.suras.getOrNull(n - 1)?.let { meta -> ReciteSession.targets(QuranListening.ayat(n, meta.ayas)) { c.quran.text(it) } } },
            ),
        )
    }

    /**
     * Takes passage [id] out of the list ([hide]) or puts it back. Hidden, it is neither listed,
     * offered to learn, reviewed nor played with the others; what was learnt of it is kept. What
     * was in progress with it stops: listening, the lesson or the test to resume.
     */
    fun hideFadila(id: String, hide: Boolean) {
        settings.update { it.copy(hiddenFadail = if (hide) it.hiddenFadail + id else it.hiddenFadail - id) }
        content.hide(settings.current.hiddenFadail)
        if (!hide) return
        nowPlaying.value?.session?.let { s -> if (s.fadilaId == id || s.fadilaId == PlaySession.ALL) player.pause() }
        sessions.session.value?.let { s ->
            if (s.learn?.fadilaId == id || s.test?.fadilaId == id || s.openFadila == id) sessions.update {
                it.copy(learn = it.learn?.takeIf { l -> l.fadilaId != id }, test = it.test?.takeIf { t -> t.fadilaId != id }, openFadila = it.openFadila?.takeIf { o -> o != id })
            }
        }
    }

    /** Every faḍīla, one after the other in the reference list's order, as one playlist. */
    fun playAll(title: String, lang: String) {
        val c = content.content.value ?: return
        val ayat = c.fadail.flatMap { it.ayat }
        if (ayat.isEmpty()) return
        val parts = c.fadail.map { PlaySession.Part(it.id, it.title.pick(lang), it.ayat.size) }
        player.play(PlaySession(PlaySession.ALL, title, ayat, 0, ayat.lastIndex, parts = parts))
    }

    /**
     * Sūra [sura] as the Qur'an section plays it: through, once, whatever the passages' repeats
     * and pauses are; the engine then goes on with the next sūra.
     */
    fun quranSession(sura: Int): PlaySession? {
        val meta = content.content.value?.suras?.getOrNull(sura - 1) ?: return null
        val ayat = QuranListening.ayat(sura, meta.ayas)
        return PlaySession(
            QuranListening.id(sura), meta.tname, ayat, 0, ayat.lastIndex,
            repeatEach = 1, repeatRange = 1, gap = GapMode.NONE, basmala = true,
        )
    }

    /**
     * Who recites [ayat] (a sūra) in the Qur'an section: the reciter chosen there, or, for a sūra
     * a whole-sūra recitation isn't cut into āyāt for yet, one that has every āya.
     */
    fun quranReciter(ayat: List<AyahRef>, s: app.hfd.data.AppSettings = settings.current): app.hfd.core.playback.Reciter {
        val choice = s.quranChoice
        return QuranListening.reciterFor(choice, timings.of(choice), ayat)
    }

    /** Listens to the Qur'an from sūra [sura] ([start]: from that āya of it). */
    fun playSura(sura: Int, start: AyahRef? = null) {
        quranSession(sura)?.let { player.play(it, start) }
    }

    fun closeRecite() {
        _recite.value?.close()
        _recite.value = null
    }

    /** Set by [app.hfd.playback.PlaybackService] while it runs (same process). */
    val engine = MutableStateFlow<PlaybackEngine?>(null)

    /** What the player is shown as holding, without playing (the screenshot test). */
    val playingPreview = MutableStateFlow<NowPlaying?>(null)

    /** What's playing, from the service's engine: session, plan, current item, sleep timer. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val nowPlaying: StateFlow<NowPlaying?> by lazy {
        engine.flatMapLatest { it?.state ?: flowOf(null) }.combine(playingPreview) { real, shown -> shown ?: real }
            .stateIn(scope, SharingStarted.Eagerly, null)
    }

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun toast(message: String) {
        _messages.tryEmit(message)
    }

    fun toast(@StringRes res: Int, vararg args: Any) = toast(app.getString(res, *args))

    fun init(application: Application) {
        app = application
        Whisper.libraryDir = application.applicationInfo.nativeLibraryDir
        Diag.start(http, scope) { settings.current.sendDiagnostics }
        ReciteSession.recordings = { settings.current.sendRecordings }
        ReciteSession.tajweed = { settings.current.reciteTajweed }
        ReciteSession.audioManager = { application.getSystemService(AudioManager::class.java) }
        ReciteSession.headset = { settings.current.reciteHeadset }
        ReciteSession.npuEncoder = { npu.takeIf { it.compiled != null } }
        // Downloaded once in the background (18 MB) on a phone it was compiled for.
        if (npu.compiled != null) scope.launch { npu.fetch() }
        ReciteSession.referenceClip = {
            runCatching {
                application.assets.open("diag/112001.wav").use { input ->
                    val bytes = input.readBytes()
                    // 16-bit mono PCM: the "data" chunk.
                    var at = 12
                    while (at + 8 <= bytes.size && String(bytes, at, 4, Charsets.US_ASCII) != "data") {
                        at += 8 + java.nio.ByteBuffer.wrap(bytes, at + 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
                    }
                    val start = at + 8
                    val b = java.nio.ByteBuffer.wrap(bytes, start, bytes.size - start).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    FloatArray((bytes.size - start) / 2) { b.short / 32768f }
                }
            }.getOrNull()
        }
        content.content // start loading the text in the background right away
        // The passages shown follow the setting however it changes (a backup read back too).
        scope.launch { settings.state.map { it.hiddenFadail }.distinctUntilChanged().collect { content.hide(it) } }
        audio.files // and indexing the āya files on the phone
        progress.state // and rebuilding progress from its log
    }
}
