package app.hfd.data

import android.content.Context
import app.hfd.core.quran.AyahRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/** An āya kept from the reading view, and when. */
@Serializable
data class Favorite(val sura: Int, val aya: Int, val at: Long = 0) {
    val ref: AyahRef get() = AyahRef(sura, aya)
}

/**
 * The āyāt the reader marked while reading the Qur'an section (progress/, so they are backed up
 * with the rest), the latest first.
 */
class Favorites(context: Context) {
    private val file = JsonFile(File(context.filesDir, "progress/favorites.json"), ListSerializer(Favorite.serializer())) { emptyList() }
    private val _list = MutableStateFlow(file.read())
    val list: StateFlow<List<Favorite>> = _list.asStateFlow()

    operator fun contains(ref: AyahRef): Boolean = _list.value.any { it.sura == ref.sura && it.aya == ref.aya }

    /** Marks [ref], or takes its mark away; returns whether it is marked now. */
    fun toggle(ref: AyahRef): Boolean {
        val had = ref in this
        save(if (had) _list.value.filterNot { it.ref == ref } else listOf(Favorite(ref.sura, ref.aya, System.currentTimeMillis())) + _list.value)
        return !had
    }

    /** A backup's marks added to the phone's (those already here keep their date). */
    fun merge(others: List<Favorite>) {
        val have = _list.value.map { it.ref }.toSet()
        val new = others.filter { it.sura in 1..114 && it.aya >= 1 && it.ref !in have }.distinctBy { it.ref }
        if (new.isNotEmpty()) save((_list.value + new).sortedByDescending { it.at })
    }

    private fun save(list: List<Favorite>) {
        _list.value = list
        file.write(list)
    }
}
