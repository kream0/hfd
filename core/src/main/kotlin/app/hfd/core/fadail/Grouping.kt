package app.hfd.core.fadail

import java.time.DayOfWeek
import java.time.LocalDateTime

/** A section of the faḍāʾil list. */
sealed interface FadailGroup {
    data class ByOccasion(val occasion: Occasion) : FadailGroup
    /** Whole long sūras recited at any time (al-Baqara, Āl ʿImrān…). */
    data object Long : FadailGroup
    /** Weak and fabricated narrations, only when the setting is on. */
    data object Weak : FadailGroup
}

object FadailGrouping {
    /** Order of the list: the day's rhythm first, then "any time", long sūras, weak narrations. */
    val OCCASION_ORDER = listOf(
        Occasion.MORNING, Occasion.EVENING, Occasion.AFTER_PRAYER, Occasion.NIGHT,
        Occasion.BEFORE_SLEEP, Occasion.FRIDAY, Occasion.ANY,
    )

    /** An entry with several occasions appears in each of them. Empty groups are dropped. */
    fun group(all: List<Fadila>, showWeak: Boolean): List<Pair<FadailGroup, List<Fadila>>> {
        val visible = all.filter { it.visible(showWeak) }
        val sound = visible.filter { it.grading.grade.acceptable }
        val long = sound.filter { it.isLong && it.occasions == listOf(Occasion.ANY) }
        val out = mutableListOf<Pair<FadailGroup, List<Fadila>>>()
        for (o in OCCASION_ORDER) {
            val items = sound.filter { o in it.occasions && it !in long }
            if (items.isNotEmpty()) out += FadailGroup.ByOccasion(o) to items
        }
        if (long.isNotEmpty()) out += FadailGroup.Long to long
        val weak = visible.filterNot { it.grading.grade.acceptable }
        if (weak.isNotEmpty()) out += FadailGroup.Weak to weak
        return out
    }

    /**
     * Occasions that fit this moment, for the home screen. Rough local-time windows: they only
     * suggest; prayer times aren't computed.
     */
    fun now(time: LocalDateTime): List<Occasion> {
        val h = time.hour
        val out = mutableListOf<Occasion>()
        if (time.dayOfWeek == DayOfWeek.FRIDAY) out += Occasion.FRIDAY
        when (h) {
            in 4..10 -> out += Occasion.MORNING
            in 15..19 -> out += Occasion.EVENING
            in 20..23, in 0..3 -> {
                out += Occasion.NIGHT
                out += Occasion.BEFORE_SLEEP
            }
        }
        return out
    }
}
