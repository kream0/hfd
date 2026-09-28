package app.hfd

import android.app.Application
import androidx.annotation.StringRes
import app.hfd.core.quran.AyahRef
import app.hfd.data.ContentRepo
import app.hfd.data.ProgressRepo
import app.hfd.data.SessionStore
import app.hfd.data.RangeStore
import app.hfd.data.Settings
import app.hfd.download.AudioDownloader
import app.hfd.download.AudioStore
import app.hfd.playback.NowPlaying
import app.hfd.playback.PlaybackEngine
import app.hfd.playback.TimingsRepo
import app.hfd.playback.PlayerConnection
import app.hfd.recite.ModelState
import app.hfd.recite.ModelStore
import app.hfd.recite.ReciteSession
import app.hfd.update.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
    val content: ContentRepo by lazy { ContentRepo(app, scope) }
    val audio: AudioStore by lazy { AudioStore(app, scope) }
    val timings: TimingsRepo by lazy { TimingsRepo(app) }
    val downloads: AudioDownloader by lazy { AudioDownloader(app, audio, http, scope, timings) }
    val player: PlayerConnection by lazy { PlayerConnection(app) }
    val ranges: RangeStore by lazy { RangeStore(app) }
    val progress: ProgressRepo by lazy { ProgressRepo(app, scope) }
    val sessions: SessionStore by lazy { SessionStore(app) }
    /** Speech model for the Recite mode (downloaded once). */
    val speech: ModelStore by lazy { ModelStore(app, http, scope) }

    private val _recite = MutableStateFlow<ReciteSession?>(null)
    /** The recitation in progress (Recite mode), if any. */
    val recite: StateFlow<ReciteSession?> = _recite.asStateFlow()

    /** Starts reciting [refs] of passage [fadilaId] aloud; replaces any recitation in progress. */
    fun openRecite(fadilaId: String, refs: List<AyahRef>) {
        closeRecite()
        val c = content.content.value ?: return
        _recite.value = ReciteSession(
            fadilaId,
            ReciteSession.targets(refs) { c.quran.text(it) },
            scope,
            model = { (speech.state.value as? ModelState.Ready)?.file },
            record = { progress.record(it) },
        )
    }

    fun closeRecite() {
        _recite.value?.close()
        _recite.value = null
    }

    /** Set by [app.hfd.playback.PlaybackService] while it runs (same process). */
    val engine = MutableStateFlow<PlaybackEngine?>(null)

    /** What's playing, from the service's engine: session, plan, current item, sleep timer. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val nowPlaying: StateFlow<NowPlaying?> by lazy {
        engine.flatMapLatest { it?.state ?: flowOf(null) }.stateIn(scope, SharingStarted.Eagerly, null)
    }

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun toast(message: String) {
        _messages.tryEmit(message)
    }

    fun toast(@StringRes res: Int, vararg args: Any) = toast(app.getString(res, *args))

    fun init(application: Application) {
        app = application
        content.content // start loading the text in the background right away
        audio.files // and indexing the āya files on the phone
        progress.state // and rebuilding progress from its log
    }
}
