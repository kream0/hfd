package app.hfd.data

import android.content.Context
import app.hfd.core.progress.LearnState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import java.io.File

@Serializable
enum class AppMode {
    @SerialName("listen") LISTEN,
    @SerialName("learn") LEARN,
    @SerialName("review") REVIEW,
    @SerialName("test") TEST,
}

/** A full test of a faḍīla in progress. */
@Serializable
data class TestState(val fadilaId: String, val index: Int = 0, val good: Int = 0, val revealed: Boolean = false)

/**
 * Where the owner left off, per mode, so the app reopens exactly there: the faḍīla, the Learn
 * step, the test position. (Listening position and repetition are saved by the player itself.)
 */
@Serializable
data class AppSession(
    val mode: AppMode = AppMode.LISTEN,
    val fadilaId: String? = null,
    val learn: LearnState? = null,
    val test: TestState? = null,
    /** What was on screen: tab, the open faḍīla, the open practice flow ("learn", "review", "test:<id>"). */
    val openTab: String? = null,
    val openFadila: String? = null,
    val openFlow: String? = null,
    val at: Long = System.currentTimeMillis(),
)

class SessionStore(context: Context) {
    private val file = JsonFile(File(context.filesDir, "progress/session.json"), AppSession.serializer().nullable) { null }
    private val _session = MutableStateFlow(file.read())
    val session: StateFlow<AppSession?> = _session.asStateFlow()

    fun update(block: (AppSession) -> AppSession) {
        val next = block(_session.value ?: AppSession()).copy(at = System.currentTimeMillis())
        _session.value = next
        file.write(next)
    }
}
