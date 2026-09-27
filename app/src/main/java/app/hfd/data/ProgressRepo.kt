package app.hfd.data

import android.content.Context
import android.util.Log
import app.hfd.core.progress.Event
import app.hfd.core.progress.EventLog
import app.hfd.core.progress.ProgressBook
import app.hfd.core.progress.ProgressState
import app.hfd.core.srs.Fsrs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.time.ZoneId
import java.util.concurrent.Executors

@Serializable
private data class Snapshot(val schema: Int, val bytes: Long, val state: ProgressState)

/**
 * Per-āya progress. The source of truth is the append-only log progress/events.jsonl (one
 * event per line); the state is rebuilt from it, and a snapshot (progress/state.json, with the
 * log length it covers) makes startup fast. Both live in progress/, which Auto Backup includes.
 */
class ProgressRepo(context: Context, private val scope: CoroutineScope) {
    private val dir = File(context.filesDir, "progress")
    val logFile = File(dir, "events.jsonl")
    private val snapshotFile = JsonFile(File(dir, "state.json"), Snapshot.serializer().nullable) { null }

    /** All file access happens on this single thread, in order. */
    private val io = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    val fsrs = Fsrs()
    val zone: ZoneId get() = ZoneId.systemDefault()

    private var book: ProgressBook? = null
    private val _state = MutableStateFlow(ProgressState())
    val state: StateFlow<ProgressState> = _state.asStateFlow()
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()
    private val pending = mutableListOf<Event>()
    private var snapshotJob: Job? = null

    init {
        scope.launch {
            val loaded = withContext(io) { load() }
            val b = ProgressBook(loaded, fsrs, zone)
            // Events recorded while loading were already written; apply them now.
            pending.forEach(b::apply)
            pending.clear()
            book = b
            _state.value = b.snapshot()
            _loaded.value = true
        }
    }

    /** Appends [event] to the log and updates the state. Main thread. */
    fun record(event: Event) {
        val line = EventLog.encode(event)
        scope.launch(io) { append(line) }
        val b = book
        if (b == null) {
            pending += event
            return
        }
        b.apply(event)
        _state.value = b.snapshot()
        scheduleSnapshot()
    }

    fun record(events: List<Event>) = events.forEach(::record)

    /** Writes the snapshot now (e.g. when the app goes to the background). */
    fun flush() {
        snapshotJob?.cancel()
        scope.launch(io) { writeSnapshot() }
    }

    /** Raw log lines, for export. */
    suspend fun logLines(): List<String> = withContext(io) {
        if (logFile.exists()) logFile.readLines().filter { it.isNotBlank() } else emptyList()
    }

    /**
     * Merges imported log lines with ours (duplicates dropped, ordered by time), rewrites the log
     * and rebuilds everything from it. Returns how many new events came in.
     */
    suspend fun importLines(lines: List<String>): Int {
        val merged = withContext(io) {
            val mine = if (logFile.exists()) logFile.readLines().filter { it.isNotBlank() } else emptyList()
            val known = mine.toHashSet()
            val incoming = lines.filter { it.isNotBlank() && it !in known && EventLog.decode(it) != null }.distinct()
            val all = (mine + incoming).mapNotNull { line -> EventLog.decode(line)?.let { it.at to line } }
                .sortedBy { it.first }
                .map { it.second }
            // Unreadable lines of ours are kept at the end rather than dropped.
            val unreadable = mine.filter { EventLog.decode(it) == null }
            dir.mkdirs()
            val tmp = File(dir, "events.jsonl.tmp")
            tmp.writeText((all + unreadable).joinToString("\n", postfix = "\n"))
            if (!tmp.renameTo(logFile)) {
                logFile.delete()
                tmp.renameTo(logFile)
            }
            snapshotFile.delete()
            incoming.size to load()
        }
        val b = ProgressBook(merged.second, fsrs, zone)
        book = b
        _state.value = b.snapshot()
        return merged.first
    }

    // ------------------------------------------------------------------ internals (io thread)

    private fun append(line: String) {
        try {
            dir.mkdirs()
            FileOutputStream(logFile, true).use { out ->
                out.write((line + "\n").toByteArray())
                out.fd.sync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't append to the progress log", e)
        }
    }

    private fun load(): ProgressState {
        val snap = snapshotFile.read()
        val length = if (logFile.exists()) logFile.length() else 0L
        val fromSnapshot = snap != null && snap.schema == SCHEMA && snap.bytes <= length
        val start = if (fromSnapshot) snap!!.bytes else 0L
        val b = ProgressBook(if (fromSnapshot) snap!!.state else ProgressState(), fsrs, zone)
        if (length > start) {
            RandomAccessFile(logFile, "r").use { raf ->
                raf.seek(start)
                val bytes = ByteArray((length - start).toInt())
                raf.readFully(bytes)
                bytes.decodeToString().lineSequence().mapNotNull(EventLog::decode).forEach(b::apply)
            }
        }
        val state = b.snapshot()
        if (!fromSnapshot || length > start) snapshotFile.write(Snapshot(SCHEMA, length, state))
        return state
    }

    /** Brings the snapshot up to the end of the log (replaying only what it doesn't cover). */
    private fun writeSnapshot() {
        load()
    }

    private fun scheduleSnapshot() {
        snapshotJob?.cancel()
        snapshotJob = scope.launch {
            delay(30_000)
            launch(io) { writeSnapshot() }
        }
    }

    companion object {
        private const val TAG = "ProgressRepo"
        private const val SCHEMA = 1
    }
}
