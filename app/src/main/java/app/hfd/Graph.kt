package app.hfd

import android.app.Application
import androidx.annotation.StringRes
import app.hfd.data.ContentRepo
import app.hfd.data.Settings
import app.hfd.update.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
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

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun toast(message: String) {
        _messages.tryEmit(message)
    }

    fun toast(@StringRes res: Int, vararg args: Any) = toast(app.getString(res, *args))

    fun init(application: Application) {
        app = application
        content // start loading the text in the background right away
    }
}
