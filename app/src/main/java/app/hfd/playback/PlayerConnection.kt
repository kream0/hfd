package app.hfd.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.hfd.Graph
import app.hfd.core.playback.MediaIds
import app.hfd.core.quran.AyahRef
import app.hfd.data.AppMode
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PlayerUiState(
    val connected: Boolean = false,
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = false,
    val isBuffering: Boolean = false,
    val mediaId: String? = null,
    /** The playing item's āya / repetition, parsed from its mediaId. */
    val item: MediaIds.Parsed? = null,
    val durationMs: Long = 0,
    val error: String? = null,
)

data class PlayerProgress(val positionMs: Long = 0, val bufferedMs: Long = 0)

/** UI-side handle on [PlaybackService] through a Media3 [MediaController]. */
class PlayerConnection(private val context: Context) {

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val _progress = MutableStateFlow(PlayerProgress())
    val progress: StateFlow<PlayerProgress> = _progress.asStateFlow()

    private var controller: MediaController? = null
    private var future: ListenableFuture<MediaController>? = null
    private val pending = mutableListOf<(MediaController) -> Unit>()
    private var ticker: Job? = null

    /** Off in screenshot tests, which render screens without the media service. */
    var connectable = true

    fun connect() {
        if (!connectable || controller != null || future != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val f = MediaController.Builder(context, token).buildAsync()
        future = f
        f.addListener({
            val c = runCatching { f.get() }.getOrNull()
            if (c == null) {
                future = null
                return@addListener
            }
            controller = c
            c.addListener(listener)
            refresh()
            val actions = pending.toList()
            pending.clear()
            actions.forEach { it(c) }
        }, ContextCompat.getMainExecutor(context))
    }

    fun disconnect() {
        controller?.removeListener(listener)
        future?.let { MediaController.releaseFuture(it) }
        controller = null
        future = null
        ticker?.cancel()
        _state.value = _state.value.copy(connected = false)
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()
    }

    private fun refresh() {
        val c = controller ?: return
        val id = c.currentMediaItem?.mediaId
        _state.value = PlayerUiState(
            connected = true,
            isPlaying = c.isPlaying,
            playWhenReady = c.playWhenReady,
            isBuffering = c.playbackState == Player.STATE_BUFFERING,
            mediaId = id,
            item = id?.let { MediaIds.parse(it) },
            durationMs = c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0,
            error = c.playerError?.let { it.cause?.message ?: it.errorCodeName },
        )
        _progress.value = PlayerProgress(c.currentPosition, c.bufferedPosition)
        if (c.isPlaying) startTicker() else ticker?.cancel()
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = Graph.scope.launch {
            while (isActive) {
                controller?.let { _progress.value = PlayerProgress(it.currentPosition, it.bufferedPosition) }
                delay(200)
            }
        }
    }

    private fun withController(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) block(c) else {
            pending += block
            connect()
        }
    }

    // ------------------------------------------------------------------ commands

    /** Plays [session] from [start]; the service builds the plan (repeats, gaps, basmala). */
    fun play(session: PlaySession, start: AyahRef? = null) {
        // Plain listening (not a Learn / Review step) is what "continue" resumes.
        if (session.tag == null) Graph.sessions.update { it.copy(mode = AppMode.LISTEN, fadilaId = session.fadilaId) }
        withController { Graph.engine.value?.play(session, start) }
    }

    fun togglePlay() = withController { c ->
        val active = c.playWhenReady && c.playbackState != Player.STATE_ENDED && c.playbackState != Player.STATE_IDLE
        if (active) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition(0)
            c.play()
        }
    }

    fun pause() = withController { it.pause() }
    fun nextAyah() = withController { it.seekToNext() }
    fun previousAyah() = withController { it.seekToPrevious() }
    fun seekTo(positionMs: Long) = withController {
        it.seekTo(positionMs)
        _progress.value = _progress.value.copy(positionMs = positionMs)
    }

    fun sleepAfter(minutes: Int) = withController { Graph.engine.value?.sleepAfter(minutes) }
    fun sleepAtEnd() = withController { Graph.engine.value?.sleepAtEnd() }
    fun cancelSleep() = withController { Graph.engine.value?.cancelSleep() }
}
