package app.hfd.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import app.hfd.core.playback.EveryAyah
import app.hfd.core.playback.Reciter
import app.hfd.core.quran.AyahRef
import app.hfd.diag.Diag
import app.hfd.playback.TimingsRepo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.FileOutputStream
import java.io.IOException

enum class DlStatus { QUEUED, WAITING_NETWORK, RUNNING, FAILED }

data class AudioTask(
    val reciter: Reciter,
    val ref: AyahRef,
    val status: DlStatus,
    val bytes: Long = 0,
    val total: Long = -1,
    val error: String? = null,
) {
    val progress: Float get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
}

/**
 * Downloads āya MP3s from everyayah.com (small files: a few hundred KB each), or an āya's bytes of
 * a whole-sūra recitation ([TimingsRepo]). Same pattern as
 * ytune's downloader: `.part` files that resume with a Range request, retries with back-off,
 * waiting for the network to come back. Bookkeeping on the main thread, transfers on IO.
 */
class AudioDownloader(
    context: Context,
    private val store: AudioStore,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    private val timings: TimingsRepo,
) {
    private val _tasks = MutableStateFlow<Map<String, AudioTask>>(emptyMap())
    /** Pending and failed downloads by [AudioStore.key]; finished ones leave the map. */
    val tasks: StateFlow<Map<String, AudioTask>> = _tasks.asStateFlow()

    private val jobs = HashMap<String, Job>()
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    init {
        runCatching {
            connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    scope.launch { retryFailed(); pump() }
                }
            })
        }
    }

    /** Queues whatever of [refs] isn't on the phone yet (failed ones are retried). */
    fun ensure(reciter: Reciter, refs: Collection<AyahRef>) {
        var added = false
        _tasks.update { current ->
            val next = LinkedHashMap(current)
            for (ref in refs.distinct()) {
                val key = store.key(reciter, ref)
                if (store.has(key)) continue
                val existing = next[key]
                if (existing != null && existing.status != DlStatus.FAILED) continue
                next[key] = AudioTask(reciter, ref, DlStatus.QUEUED)
                added = true
            }
            next
        }
        if (added) pump()
    }

    /** Offline progress of [refs] for [reciter]: 0 = nothing saved, 1 = all saved. */
    fun progress(reciter: Reciter, refs: Collection<AyahRef>, files: Set<String>, tasks: Map<String, AudioTask>): Float {
        if (refs.isEmpty()) return 1f
        var sum = 0f
        for (ref in refs) {
            val key = store.key(reciter, ref)
            sum += if (key in files) 1f else tasks[key]?.progress ?: 0f
        }
        return sum / refs.size
    }

    private fun retryFailed() {
        _tasks.update { m -> m.mapValues { (_, t) -> if (t.status == DlStatus.FAILED) t.copy(status = DlStatus.QUEUED, error = null) else t } }
    }

    private fun online(): Boolean {
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun pump() {
        val canRun = online()
        var running = _tasks.value.values.count { it.status == DlStatus.RUNNING }
        for ((key, task) in _tasks.value) {
            if (task.status != DlStatus.QUEUED && task.status != DlStatus.WAITING_NETWORK) continue
            if (!canRun) {
                if (task.status != DlStatus.WAITING_NETWORK) mutate(key) { it.copy(status = DlStatus.WAITING_NETWORK) }
            } else if (running < PARALLEL) {
                running++
                start(key, task)
            }
        }
    }

    private fun start(key: String, task: AudioTask) {
        mutate(key) { it.copy(status = DlStatus.RUNNING, error = null) }
        jobs[key] = scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { download(key, task) } }
            jobs.remove(key)
            result.onSuccess { _tasks.update { it - key } }
            result.onFailure { e ->
                if (e is CancellationException) return@onFailure
                Log.w(TAG, "Download failed for $key", e)
                mutate(key) { it.copy(status = if (online()) DlStatus.FAILED else DlStatus.WAITING_NETWORK, error = e.message) }
            }
            pump()
        }
    }

    private fun mutate(key: String, block: (AudioTask) -> AudioTask) {
        _tasks.update { m -> m[key]?.let { m + (key to block(it)) } ?: m }
    }

    private suspend fun download(key: String, task: AudioTask) {
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                transfer(key, task)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                attempt++
                Diag.error("download.error", e, "key" to key, "attempt" to attempt)
                if (attempt >= 3) throw e
                delay(1500L * attempt)
            }
        }
    }

    private suspend fun transfer(key: String, task: AudioTask) {
        val ctx = currentCoroutineContext()
        val target = store.file(task.reciter, task.ref)
        target.parentFile?.mkdirs()
        val part = java.io.File(target.parentFile, target.name + ".part")
        var offset = part.length()
        // A whole-sūra recitation: the āya is a byte range of its sūra's file.
        val t = timings.of(task.reciter)
        val range = t?.let { it.bytes(task.ref) ?: throw IOException("No timing for ${task.ref}") }
        val request = Request.Builder()
            .url(if (t != null) t.url(task.ref) ?: throw IOException("No file for ${task.ref}") else EveryAyah.url(task.reciter, task.ref))
            .apply {
                when {
                    range != null -> header("Range", "bytes=${range.first + offset}-${range.last}")
                    offset > 0 -> header("Range", "bytes=$offset-")
                }
            }
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code == 416) {
                // Already complete.
            } else {
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("Empty response")
                if (range != null) {
                    // Only these bytes, of the very file the timings were made from.
                    if (response.code != 206) throw IOException("No range support")
                    val size = response.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                    if (size != null && size != t?.sura(task.ref)?.size) throw IOException("The recitation's file changed")
                }
                val append = response.code == 206 && offset > 0
                if (!append) offset = 0
                val total = when {
                    range != null -> range.last - range.first + 1
                    append -> response.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1
                    else -> body.contentLength()
                }
                FileOutputStream(part, append).use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(32 * 1024)
                        var done = offset
                        var lastReport = 0L
                        while (true) {
                            ctx.ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            done += n
                            val now = System.currentTimeMillis()
                            if (now - lastReport > 250) {
                                lastReport = now
                                val d = done
                                scope.launch { mutate(key) { it.copy(bytes = d, total = total) } }
                            }
                        }
                    }
                }
                if (total > 0 && part.length() < total) throw IOException("Incomplete download")
            }
        }
        if (part.length() == 0L) throw IOException("Empty file")
        target.delete()
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
        store.added(task.reciter, task.ref, target)
    }

    companion object {
        private const val TAG = "AudioDownloader"
        private const val PARALLEL = 3
    }
}
