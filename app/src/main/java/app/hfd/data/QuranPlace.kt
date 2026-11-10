package app.hfd.data

import android.content.Context
import app.hfd.core.quran.AyahRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.nullable
import java.io.File

/**
 * The āya the Qur'an section was last listened to at (progress/, so it's backed up with the rest):
 * where "resume" starts again, whatever passage was played in between.
 */
class QuranPlace(context: Context) {
    private val file = JsonFile(File(context.filesDir, "progress/quran.json"), AyahRef.serializer().nullable) { null }
    private val _place = MutableStateFlow(file.read())
    val place: StateFlow<AyahRef?> = _place.asStateFlow()

    fun set(ref: AyahRef) {
        if (_place.value == ref) return
        _place.value = ref
        file.write(ref)
    }
}
