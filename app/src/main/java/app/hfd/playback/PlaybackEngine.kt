package app.hfd.playback

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import app.hfd.Graph
import app.hfd.R
import app.hfd.core.playback.Cursor
import app.hfd.core.playback.GapMode
import app.hfd.core.playback.PlanItem
import app.hfd.core.playback.PlanSpec
import app.hfd.core.playback.PlaybackPlan
import app.hfd.core.playback.Reciter
import app.hfd.core.playback.Reciters
import app.hfd.core.playback.Timing
import app.hfd.core.progress.Event
import app.hfd.core.quran.AyahRef
import app.hfd.data.AppSettings
import app.hfd.data.JsonFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import java.io.File

/**
 * What is being listened to: a faḍīla, or a sub-range of it (indices into its āyāt); or every
 * faḍīla one after the other ([ALL], with its [parts]).
 */
@Serializable
data class PlaySession(
    val fadilaId: String,
    val title: String,
    /** The āyāt of the sub-range being played, in order. */
    val ayat: List<AyahRef>,
    /** Position of the sub-range within the faḍīla (for the range picker). */
    val from: Int,
    val to: Int,
    /** Overrides of the listening settings (Learn steps play with their own repeats and gaps). */
    val repeatEach: Int? = null,
    val repeatRange: Int? = null,
    val gap: GapMode? = null,
    val basmala: Boolean? = null,
    /** Lets a caller recognise the end of its own request (e.g. a Learn step). */
    val tag: String? = null,
    /** Playing all: each faḍīla's run of [ayat], in order. */
    val parts: List<Part> = emptyList(),
) {
    @Serializable
    data class Part(val fadilaId: String, val title: String, val count: Int)

    /** The faḍīla āya [index] of [ayat] belongs to (itself, unless playing all). */
    fun fadilaAt(index: Int): String = partAt(index)?.fadilaId ?: fadilaId

    fun titleAt(index: Int): String = partAt(index)?.title ?: title

    private fun partAt(index: Int): Part? {
        var end = 0
        for (p in parts) {
            end += p.count
            if (index < end) return p
        }
        return parts.lastOrNull()
    }

    companion object {
        const val ALL = "all"
    }
}

/** Saved on every change so playback resumes exactly there (in the app or from earbuds). */
@Serializable
data class Resume(
    val session: PlaySession,
    val reciter: String,
    val mediaId: String,
    val positionMs: Long,
    val savedAt: Long = System.currentTimeMillis(),
)

sealed interface Sleep {
    data class At(val endsAtMs: Long) : Sleep
    data object EndOfFadila : Sleep
}

/** Engine state for the UI (same process). */
data class NowPlaying(
    val session: PlaySession,
    val spec: PlanSpec,
    val reciter: Reciter,
    val item: PlanItem?,
    val sleep: Sleep?,
    /** The plan played to its end. */
    val ended: Boolean = false,
) {
    /** The faḍīla playing (when playing all, the one of the current āya). */
    val fadilaId: String get() = session.fadilaAt(item?.cursor?.index ?: 0)
    val title: String get() = session.titleAt(item?.cursor?.index ?: 0)
}

/**
 * Turns a [PlanSpec] into what ExoPlayer plays. The plan can be endless, so the player gets a
 * window of items that's extended as it plays (and trimmed behind). Next / previous move by
 * whole āyāt; a change of reciter, repeats or gap rebuilds the window from the current āya.
 */
class PlaybackEngine(
    private val context: Context,
    private val player: ExoPlayer,
    private val scope: CoroutineScope,
) {
    private val resumeFile = JsonFile(File(context.filesDir, "progress/resume.json"), Resume.serializer().nullable) { null }

    private var session: PlaySession? = null
    private var spec: PlanSpec? = null
    private var reciter: Reciter = Graph.settings.current.reciterInfo
    private var applied: AppSettings = Graph.settings.current
    private var sleepJob: Job? = null
    private var endOfFadila = false

    private val _state = MutableStateFlow<NowPlaying?>(null)
    val state: StateFlow<NowPlaying?> = _state.asStateFlow()

    private var saveJob: Job? = null
    private val listening = ListenTracker()

    init {
        scope.launch {
            while (isActive) {
                listening.tick()
                delay(TICK_MS)
            }
        }
        player.setPlaybackSpeed(applied.speed)
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                extendWindow()
                publish()
                scheduleSave()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) onReady()
                if (playbackState == Player.STATE_ENDED) onEnded()
                publish()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) listening.flush()
                scheduleSave()
            }
        })
    }

    // ------------------------------------------------------------------ commands

    /** Plays [session] from [start] (its first āya by default). */
    fun play(session: PlaySession, start: AyahRef? = null, rep: Int = 1) {
        listening.flush()
        this.session = session
        endOfFadila = false
        if (_state.value?.sleep == Sleep.EndOfFadila) cancelSleep()
        reciter = Graph.settings.current.reciterInfo
        val spec = specFor(session, Graph.settings.current)
        this.spec = spec
        val index = start?.let { spec.ayat.indexOf(it) }?.takeIf { it >= 0 } ?: 0
        load(spec, Cursor(1, index, rep.coerceAtLeast(1)), 0, play = true)
    }

    fun nextAyah() {
        val spec = spec ?: return
        val current = currentItem() ?: return
        // Past the last āya of the last pass there's nothing to skip to.
        val target = PlaybackPlan.nextAyah(spec, current.cursor) ?: return
        jumpTo(spec, target)
    }

    fun previousAyah() {
        val spec = spec ?: return
        val current = currentItem() ?: return
        // Well into the first recitation: restart this āya rather than going back.
        val restart = current is PlanItem.Ayah && current.rep == 1 && player.currentPosition > RESTART_THRESHOLD_MS
        val target = if (restart) current.cursor.copy(rep = 1) else PlaybackPlan.previousAyah(spec, current.cursor)
        jumpTo(spec, target)
    }

    fun hasNext(): Boolean {
        val spec = spec ?: return false
        val current = currentItem() ?: return false
        return PlaybackPlan.nextAyah(spec, current.cursor) != null
    }

    fun hasPrevious(): Boolean = spec != null && currentItem() != null

    /** Settings changed: speed applies right away, the rest rebuilds from the current āya. */
    fun onSettings(s: AppSettings) {
        val old = applied
        applied = s
        if (s.speed != old.speed) player.setPlaybackSpeed(s.speed)
        val structural = s.reciter != old.reciter || s.repeatEach != old.repeatEach ||
            s.repeatRange != old.repeatRange || s.gap != old.gap || s.basmala != old.basmala
        if (!structural) return
        val session = session ?: return
        val current = currentItem()
        val sameAudio = s.reciter == old.reciter
        reciter = s.reciterInfo
        val spec = specFor(session, s)
        this.spec = spec
        if (current == null) return
        val maxRep = if (spec.repeatEach == PlanSpec.INFINITE) Int.MAX_VALUE else spec.repeatEach
        val cursor = current.cursor.copy(rep = current.cursor.rep.coerceAtMost(maxRep))
        val keep = sameAudio && current is PlanItem.Ayah && cursor.rep == current.cursor.rep
        load(spec, cursor, if (keep) player.currentPosition else 0, play = player.playWhenReady, skipBasmala = current !is PlanItem.Basmala)
    }

    fun sleepAfter(minutes: Int) {
        cancelSleep()
        val endsAt = System.currentTimeMillis() + minutes * 60_000L
        sleepJob = scope.launch {
            while (isActive && System.currentTimeMillis() < endsAt) delay(1_000)
            player.pause()
            cancelSleep()
        }
        setSleep(Sleep.At(endsAt))
    }

    /** Stop at the end of the current pass over the faḍīla (∞ repeats count once more). */
    fun sleepAtEnd() {
        cancelSleep()
        endOfFadila = true
        setSleep(Sleep.EndOfFadila)
        val session = session ?: return
        val current = currentItem() ?: return
        val spec = specFor(session, applied)
        this.spec = spec
        load(spec, current.cursor, player.currentPosition, play = player.playWhenReady, skipBasmala = current !is PlanItem.Basmala)
    }

    fun cancelSleep() {
        sleepJob?.cancel()
        sleepJob = null
        if (endOfFadila) {
            endOfFadila = false
            session?.let { s ->
                val current = currentItem()
                val spec = specFor(s, applied)
                this.spec = spec
                if (current != null) load(spec, current.cursor, player.currentPosition, play = player.playWhenReady, skipBasmala = current !is PlanItem.Basmala)
            }
        }
        setSleep(null)
    }

    /** Items to resume with (earbud "play" while the app isn't running). */
    fun resumeItems(): Triple<List<MediaItem>, Int, Long>? {
        val saved = resumeFile.read() ?: return null
        session = saved.session
        reciter = Reciters.byId(saved.reciter)
        val spec = specFor(saved.session, applied)
        this.spec = spec
        val item = PlaybackPlan.decode(spec, saved.mediaId)
        val cursor = item?.cursor ?: Cursor()
        val items = PlaybackPlan.items(spec, cursor).take(WINDOW).toList()
        val found = items.indexOfFirst { it.mediaId == saved.mediaId }
        publishLater()
        return Triple(items.map { build(spec, it) }, found.coerceAtLeast(0), if (found >= 0) saved.positionMs else 0)
    }

    /** Loads the saved session into the (paused) player at startup. */
    fun restore() {
        if (player.mediaItemCount > 0) return
        val (items, index, position) = resumeItems() ?: return
        player.setMediaItems(items, index, position)
        publish()
    }

    fun saveNow() {
        listening.flush()
        saveJob?.cancel()
        snapshot()?.let { resumeFile.write(it) }
    }

    fun pause() = player.pause()

    // ------------------------------------------------------------------ internals

    private fun specFor(session: PlaySession, s: AppSettings): PlanSpec {
        var repeatEach = session.repeatEach ?: s.repeatEach
        var repeatRange = session.repeatRange ?: s.repeatRange
        if (endOfFadila) {
            if (repeatEach == PlanSpec.INFINITE) repeatEach = 1
            repeatRange = currentItem()?.cursor?.pass ?: 1
        }
        return PlanSpec(session.ayat, repeatEach, repeatRange, session.gap ?: s.gap, session.basmala ?: s.basmala)
    }

    fun currentItem(): PlanItem? {
        val spec = spec ?: return null
        val id = player.currentMediaItem?.mediaId ?: return null
        return PlaybackPlan.decode(spec, id)
    }

    private fun jumpTo(spec: PlanSpec, target: Cursor) {
        // Already in the window? Seek there (its basmala first, if it has one).
        for (i in 0 until player.mediaItemCount) {
            val item = PlaybackPlan.decode(spec, player.getMediaItemAt(i).mediaId) ?: continue
            if (item.cursor == target && item !is PlanItem.Gap) {
                player.seekTo(i, 0)
                if (player.playbackState == Player.STATE_IDLE) player.prepare()
                return
            }
        }
        load(spec, target, 0, play = player.playWhenReady)
    }

    private fun load(spec: PlanSpec, cursor: Cursor, positionMs: Long, play: Boolean, skipBasmala: Boolean = false) {
        var items = PlaybackPlan.items(spec, cursor).take(WINDOW).toList()
        if (skipBasmala && items.firstOrNull() is PlanItem.Basmala) items = items.drop(1)
        if (items.isEmpty()) return
        player.setMediaItems(items.map { build(spec, it) }, 0, positionMs)
        player.prepare()
        player.playWhenReady = play
        publish()
        scheduleSave()
    }

    private fun build(spec: PlanSpec, item: PlanItem): MediaItem {
        val session = session!!
        return Items.build(
            item = item,
            spec = spec,
            fadilaId = session.fadilaAt(item.cursor.index),
            fadilaTitle = session.titleAt(item.cursor.index),
            reciter = reciter,
            basmalaLabel = context.getString(R.string.basmala_label),
            gapMs = { gap -> Timing.gapMs(durationOf(gap.ref), gap.factor) },
        )
    }

    /** Known length of a recitation, or an estimate from its text until it's measured. */
    private fun durationOf(ref: AyahRef): Long =
        Graph.audio.durationMs(reciter, ref)
            ?: Timing.estimateMs(Graph.content.content.value?.text(ref).orEmpty(), reciter)

    /** Keeps a few dozen items ahead of the one playing; drops what's long past. */
    private fun extendWindow() {
        val spec = spec ?: return
        val count = player.mediaItemCount
        if (count == 0) return
        val index = player.currentMediaItemIndex
        if (count - index < LOW_WATER) {
            val last = PlaybackPlan.decode(spec, player.getMediaItemAt(count - 1).mediaId)
            if (last != null) {
                val more = PlaybackPlan.after(spec, last).take(WINDOW).toList()
                if (more.isNotEmpty()) player.addMediaItems(more.map { build(spec, it) })
            }
        }
        if (index > TRIM_AFTER) player.removeMediaItems(0, index - KEEP_BEHIND)
    }

    /** The playing āya's real length is now known: remember it and fix the gap that follows. */
    private fun onReady() {
        val spec = spec ?: return
        val item = currentItem() as? PlanItem.Ayah ?: return
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        Graph.audio.setDuration(Graph.audio.key(reciter, item.ref), duration)
        val nextIndex = player.currentMediaItemIndex + 1
        if (nextIndex >= player.mediaItemCount) return
        val next = player.getMediaItemAt(nextIndex)
        val gap = PlaybackPlan.decode(spec, next.mediaId) as? PlanItem.Gap ?: return
        val want = Timing.gapMs(duration, gap.factor)
        val have = Items.silenceMs(next) ?: return
        if (kotlin.math.abs(want - have) > 150) player.replaceMediaItem(nextIndex, build(spec, gap))
    }

    private fun onEnded() {
        if (endOfFadila) {
            endOfFadila = false
            setSleep(null)
        }
        scheduleSave()
    }

    private fun setSleep(sleep: Sleep?) {
        _state.value = _state.value?.copy(sleep = sleep)
        sleepState = sleep
    }

    private var sleepState: Sleep? = null

    private fun publish() {
        val session = session ?: return
        val spec = spec ?: return
        _state.value = NowPlaying(session, spec, reciter, currentItem(), sleepState, player.playbackState == Player.STATE_ENDED)
    }

    private fun publishLater() {
        scope.launch { publish() }
    }

    private fun snapshot(): Resume? {
        val session = session ?: return null
        val id = player.currentMediaItem?.mediaId ?: return null
        return Resume(session, reciter.id, id, player.currentPosition.coerceAtLeast(0))
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(1_000)
            val snap = snapshot() ?: return@launch
            launch(Dispatchers.IO) { resumeFile.write(snap) }
        }
    }

    /**
     * Listening statistics: time spent on each āya (its recitations and the pauses after them)
     * and recitations heard to at least 90 %, measured from actual playback progress (seeking
     * doesn't count). Consecutive repetitions of an āya become one event.
     */
    private inner class ListenTracker {
        private var key: String? = null
        private var ms = 0L
        private var completions = 0
        private var itemId: String? = null
        private var itemPlayed = 0L
        private var itemDuration = 0L
        private var lastPos = -1L
        private var lastTick = 0L

        fun tick() {
            val item = currentItem()
            val id = player.currentMediaItem?.mediaId
            if (!player.isPlaying || item == null || item is PlanItem.Basmala || id == null) {
                closeItem()
                lastTick = 0
                return
            }
            if (item.ref.key != key) flush()
            key = item.ref.key
            val now = android.os.SystemClock.elapsedRealtime()
            if (lastTick > 0 && now - lastTick < 3 * TICK_MS) ms += now - lastTick
            lastTick = now
            if (item !is PlanItem.Ayah) {
                closeItem()
                return
            }
            if (id != itemId) {
                closeItem()
                itemId = id
                lastPos = -1
            }
            val pos = player.currentPosition
            val d = player.duration
            if (d != C.TIME_UNSET && d > 0) itemDuration = d
            if (lastPos >= 0 && pos >= lastPos && pos - lastPos < 3 * TICK_MS * 2) itemPlayed += pos - lastPos
            lastPos = pos
        }

        /** The recitation being tracked ended (or was left): count it if ≥ 90 % was heard. */
        private fun closeItem() {
            if (itemId != null && itemDuration > 0 && itemPlayed >= itemDuration * 0.9) completions++
            itemId = null
            itemPlayed = 0
            itemDuration = 0
            lastPos = -1
        }

        fun flush() {
            closeItem()
            val k = key
            if (k != null && (ms > 0 || completions > 0)) {
                Graph.progress.record(Event.Listen(System.currentTimeMillis(), k, ms, completions, reciter.id))
            }
            key = null
            ms = 0
            completions = 0
            lastTick = 0
        }
    }

    companion object {
        private const val TICK_MS = 500L
        private const val WINDOW = 120
        private const val LOW_WATER = 40
        private const val TRIM_AFTER = 200
        private const val KEEP_BEHIND = 20
        private const val RESTART_THRESHOLD_MS = 3_000L
    }
}
