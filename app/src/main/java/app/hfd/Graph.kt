package app.hfd

import android.app.Application
import androidx.annotation.StringRes
import app.hfd.data.ContentRepo
import app.hfd.data.RangeStore
import app.hfd.data.Settings
import app.hfd.download.AudioDownloader
import app.hfd.download.AudioStore
import app.hfd.playback.NowPlaying
import app.hfd.playback.PlaybackEngine
import app.hfd.playback.PlayerConnection
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
    val downloads: AudioDownloader by lazy { AudioDownloader(app, audio, http, scope) }
    val player: PlayerConnection by lazy { PlayerConnection(app) }
    val ranges: RangeStore by lazy { RangeStore(app) }

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
    }
}
