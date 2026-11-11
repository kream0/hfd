package app.hfd.data

import android.content.Context
import app.hfd.core.quran.AyahRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import java.io.File

/**
 * Where the Qur'an was last being read: the āya at the top of the text. The one place of the
 * reader: a sūra reopened starts there (the app comes back as it was left), listening starts
 * from there, and the bar over the text shows it. With it, whether the text was following the
 * recitation ([following]) or had been taken elsewhere: reopened, it follows again or stays where
 * it was read. Kept in memory at once, written a moment after the scrolling stops.
 */
class ReadingPlace(context: Context, private val scope: CoroutineScope) {
    @Serializable
    private data class Saved(val sura: Int, val aya: Int, val following: Boolean = true)

    private val file = JsonFile(File(context.filesDir, "progress/reading.json"), Saved.serializer().nullable) { null }
    private val saved = runCatching { file.read() }.getOrNull()
    private val _place = MutableStateFlow(saved?.let { AyahRef(it.sura, it.aya) })
    val place: StateFlow<AyahRef?> = _place.asStateFlow()
    private var writing: Job? = null

    /** Whether the text was following the recitation when it was last read (else it was read elsewhere). */
    var following: Boolean = saved?.following ?: true
        set(value) {
            if (field == value) return
            field = value
            write()
        }

    /** The āya of [sura] to open it at, if it is the sūra last read. */
    fun of(sura: Int): AyahRef? = _place.value?.takeIf { it.sura == sura }

    fun set(ref: AyahRef) {
        if (_place.value == ref) return
        _place.value = ref
        write()
    }

    private fun write() {
        writing?.cancel()
        writing = scope.launch {
            delay(WRITE_AFTER_MS)
            _place.value?.let { file.write(Saved(it.sura, it.aya, following)) }
        }
    }

    private companion object {
        const val WRITE_AFTER_MS = 600L
    }
}
