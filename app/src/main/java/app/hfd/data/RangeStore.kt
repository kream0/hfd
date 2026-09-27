package app.hfd.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/** A sub-range of a faḍīla's āyāt: positions [from]..[to] in its list (inclusive). */
@Serializable
data class SubRange(val from: Int, val to: Int)

/** The sub-range chosen for each faḍīla (progress/, so it's backed up with the rest). */
class RangeStore(context: Context) {
    private val file = JsonFile(
        File(context.filesDir, "progress/ranges.json"),
        MapSerializer(String.serializer(), SubRange.serializer()),
    ) { emptyMap() }
    private val _ranges = MutableStateFlow(file.read())
    val ranges: StateFlow<Map<String, SubRange>> = _ranges.asStateFlow()

    fun get(fadilaId: String, size: Int): SubRange {
        val r = _ranges.value[fadilaId] ?: return SubRange(0, size - 1)
        val from = r.from.coerceIn(0, size - 1)
        return SubRange(from, r.to.coerceIn(from, size - 1))
    }

    fun set(fadilaId: String, range: SubRange?) {
        _ranges.value = if (range == null) _ranges.value - fadilaId else _ranges.value + (fadilaId to range)
        file.write(_ranges.value)
    }
}
