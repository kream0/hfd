package app.hfd.download

import android.content.Context
import android.media.MediaMetadataRetriever
import app.hfd.core.playback.EveryAyah
import app.hfd.core.playback.Reciter
import app.hfd.core.quran.AyahRef
import app.hfd.data.JsonFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/**
 * Āya MP3s kept on the phone: files/audio/<everyayah folder>/<SSSAAA>.mp3 (not backed up; they
 * can always be downloaded again). Also remembers each recitation's length, which gaps need.
 */
class AudioStore(context: Context, private val scope: CoroutineScope) {
    val root = File(context.filesDir, "audio")

    /** "Folder/002255.mp3" keys of the files on disk. */
    private val _files = MutableStateFlow<Set<String>>(emptySet())
    val files: StateFlow<Set<String>> = _files.asStateFlow()

    private val durationsFile = JsonFile(
        File(context.filesDir, "cache/durations.json"),
        MapSerializer(String.serializer(), Long.serializer()),
    ) { emptyMap() }
    private val durations = HashMap<String, Long>()

    init {
        scope.launch(Dispatchers.IO) {
            val found = root.listFiles()?.flatMap { dir ->
                dir.listFiles { f -> f.name.endsWith(".mp3") && f.length() > 0 }?.map { "${dir.name}/${it.name}" }.orEmpty()
            }.orEmpty()
            _files.update { it + found }
            val saved = durationsFile.read()
            synchronized(durations) { saved.forEach { (k, v) -> durations.putIfAbsent(k, v) } }
        }
    }

    fun key(reciter: Reciter, ref: AyahRef): String = "${reciter.folder}/${EveryAyah.fileName(ref)}"

    fun file(reciter: Reciter, ref: AyahRef): File = File(root, key(reciter, ref))

    fun has(reciter: Reciter, ref: AyahRef): Boolean = key(reciter, ref) in _files.value

    fun has(key: String): Boolean = key in _files.value

    /** Called by the downloader once [file] is complete. */
    fun added(reciter: Reciter, ref: AyahRef, file: File) {
        _files.update { it + key(reciter, ref) }
        if (durationMs(reciter, ref) == null) {
            val ms = runCatching {
                MediaMetadataRetriever().run {
                    try {
                        setDataSource(file.absolutePath)
                        extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    } finally {
                        release()
                    }
                }
            }.getOrNull()
            if (ms != null && ms > 0) setDuration(key(reciter, ref), ms)
        }
    }

    fun durationMs(reciter: Reciter, ref: AyahRef): Long? = synchronized(durations) { durations[key(reciter, ref)] }

    fun durationMs(key: String): Long? = synchronized(durations) { durations[key] }

    fun setDuration(key: String, ms: Long) {
        val changed = synchronized(durations) { durations.put(key, ms) != ms }
        if (changed) scope.launch(Dispatchers.IO) { durationsFile.write(synchronized(durations) { HashMap(durations) }) }
    }

    fun deleteAll() {
        root.deleteRecursively()
        _files.value = emptySet()
    }

    fun totalBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
