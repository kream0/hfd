@file:OptIn(UnstableApi::class)

package app.hfd.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.hfd.Graph
import app.hfd.MainActivity
import app.hfd.R
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Hosts the ExoPlayer + MediaSession: notification, lock screen and Bluetooth / earbud controls
 * (AVRCP next / previous arrive as seekToNext / seekToPrevious, which move by whole āyāt).
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private lateinit var exo: ExoPlayer
    private lateinit var engine: PlaybackEngine
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var retriedId: String? = null

    override fun onCreate() {
        super.onCreate()

        val remote = CacheDataSource.Factory()
            .setCache(StreamCache.get(this))
            .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(Graph.http))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val dataSourceFactory = ResolvingDataSource.Factory(
            RoutingDataSource.Factory(DefaultDataSource.Factory(this), SilenceDataSource.Factory(), remote),
            AyahResolver(Graph.audio, Graph.timings),
        )

        exo = ExoPlayer.Builder(this)
            // Constant-bitrate seeking: an āya cut from a sūra file has no Xing header to say how long it is.
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory, DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true) // pause when earbuds disconnect
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        exo.addListener(listener)

        engine = PlaybackEngine(this, exo, scope)
        Graph.engine.value = engine

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_PLAYER)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        session = MediaSession.Builder(this, AyahPlayer(exo, engine))
            .setCallback(SessionCallback())
            .setSessionActivity(openApp)
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply {
                setSmallIcon(R.drawable.ic_stat_hfd)
            }
        )

        engine.restore()

        scope.launch {
            Graph.settings.state.drop(1).collect { engine.onSettings(it) }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0 || p.playbackState == Player.STATE_ENDED) {
            engine.saveNow()
            stopSelf()
        }
    }

    override fun onDestroy() {
        if (::engine.isInitialized) engine.saveNow()
        Graph.engine.value = null
        scope.cancel()
        session?.let {
            it.player.release()
            it.release()
        }
        session = null
        super.onDestroy()
    }

    private val listener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // A restored session isn't prepared yet: prepare on the first "play" (app, notification, earbuds).
            if (playWhenReady && exo.playbackState == Player.STATE_IDLE && exo.mediaItemCount > 0) exo.prepare()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) retriedId = null
        }

        override fun onPlayerError(error: PlaybackException) {
            val item = exo.currentMediaItem ?: return
            // One retry in place (a dropped connection), then stop and say why.
            if (retriedId != item.mediaId) {
                retriedId = item.mediaId
                exo.prepare()
                return
            }
            val reason = error.cause?.message ?: error.errorCodeName
            Graph.toast(R.string.player_error, item.mediaMetadata.title ?: item.mediaId, reason)
            exo.pause()
        }
    }

    private inner class SessionCallback : MediaSession.Callback {
        /** Controllers strip URIs when handing items over; ours are rebuilt from the plan instead. */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(mediaItems)

        /** Earbud "play" while the app isn't running → pick up exactly where we left off. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val (items, index, position) = engine.resumeItems()
                ?: return Futures.immediateFailedFuture(UnsupportedOperationException("Nothing to resume"))
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(items, index, position))
        }
    }
}

/**
 * The player the session exposes: next / previous (earbuds, notification, lock screen) jump to
 * the next / previous āya instead of the next repetition or gap.
 */
private class AyahPlayer(player: ExoPlayer, private val engine: PlaybackEngine) : ForwardingPlayer(player) {
    private val jumps = listOf(
        Player.COMMAND_SEEK_TO_NEXT,
        Player.COMMAND_SEEK_TO_PREVIOUS,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
    )

    override fun getAvailableCommands(): Player.Commands =
        super.getAvailableCommands().buildUpon().addAll(*jumps.toIntArray()).build()

    override fun isCommandAvailable(command: Int): Boolean = command in jumps || super.isCommandAvailable(command)

    override fun seekToNext() = engine.nextAyah()
    override fun seekToNextMediaItem() = engine.nextAyah()
    override fun seekToPrevious() = engine.previousAyah()
    override fun seekToPreviousMediaItem() = engine.previousAyah()
    override fun hasNextMediaItem(): Boolean = engine.hasNext()
    override fun hasPreviousMediaItem(): Boolean = engine.hasPrevious()
}
