package app.hfd.core.fadail

import java.time.DayOfWeek
import java.time.LocalDateTime

object FadailGrouping {
    /** Passages whose shown narrations fit one of [occasions], in the reference order. */
    fun suggested(all: List<Fadila>, occasions: Collection<Occasion>, showWeak: Boolean): List<Fadila> =
        all.filter { f -> f.occasions(showWeak).any { it in occasions } }

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
