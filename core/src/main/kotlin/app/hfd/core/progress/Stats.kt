package app.hfd.core.progress

import app.hfd.core.fadail.Fadila
import app.hfd.core.quran.AyahRef
import app.hfd.core.srs.CardState
import app.hfd.core.srs.Fsrs
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class Streak(val current: Int, val best: Int, val todayDone: Boolean)

data class FadilaProgress(
    val memorised: Int,
    val started: Int,
    val total: Int,
    /** Earliest due date among its started āyāt. */
    val nextDue: Long?,
    /** Average strength (retrievability) of its started āyāt, 0..1. */
    val strength: Float,
) {
    val fraction: Float get() = if (total == 0) 0f else memorised.toFloat() / total
}

object Stats {
    fun today(nowMs: Long, zone: ZoneId): Long = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().toEpochDay()

    /** End of the local day containing [nowMs] (what "due today" means). */
    fun endOfDay(nowMs: Long, zone: ZoneId): Long =
        LocalDate.ofEpochDay(today(nowMs, zone) + 1).atStartOfDay(zone).toInstant().toEpochMilli() - 1

    /**
     * Days in a row meeting the daily goal. Today counts once it's met; until then the streak
     * still stands on yesterday.
     */
    fun streak(days: Map<Long, DayStats>, goalMs: Long, today: Long): Streak {
        fun met(day: Long) = (days[day]?.practiceMs ?: 0) >= goalMs
        val todayDone = met(today)
        var current = 0
        var d = if (todayDone) today else today - 1
        while (met(d)) {
            current++
            d--
        }
        var best = 0
        var run = 0
        var last = Long.MIN_VALUE
        for (day in days.keys.filter { met(it) }.sorted()) {
            run = if (day == last + 1) run + 1 else 1
            best = maxOf(best, run)
            last = day
        }
        return Streak(current, maxOf(best, current), todayDone)
    }

    /** Āyāt to review now (started, due by the end of today), in muṣḥaf order. */
    fun due(state: ProgressState, nowMs: Long, zone: ZoneId, within: Set<String>? = null): List<AyahRef> {
        val end = endOfDay(nowMs, zone)
        return state.ayat.asSequence()
            .filter { (k, p) -> p.started && p.card.due <= end && (within == null || k in within) }
            .mapNotNull { AyahRef.parse(it.key) }
            .sorted()
            .toList()
    }

    fun fadila(state: ProgressState, f: Fadila, fsrs: Fsrs, nowMs: Long): FadilaProgress {
        var memorised = 0
        var started = 0
        var nextDue: Long? = null
        var strength = 0.0
        val ayat = f.ayat
        for (ref in ayat) {
            val p = state[ref.key]
            if (!p.started) continue
            started++
            if (p.memorised) memorised++
            nextDue = minOf(nextDue ?: Long.MAX_VALUE, p.card.due)
            strength += fsrs.strength(p.card, nowMs)
        }
        return FadilaProgress(memorised, started, ayat.size, nextDue, if (started == 0) 0f else (strength / started).toFloat())
    }

    fun memorisedCount(state: ProgressState): Int = state.ayat.values.count { it.memorised }

    fun totalListenMs(state: ProgressState): Long = state.days.values.sumOf { it.listenMs }

    /** A faḍīla is ready for a full test when every āya is memorised and the last test is old. */
    fun testSuggested(state: ProgressState, f: Fadila, nowMs: Long, everyDays: Int = 30): Boolean {
        val allMemorised = f.ayat.all { state[it.key].card.state == CardState.REVIEW }
        val last = state.tests[f.id] ?: return allMemorised
        return allMemorised && nowMs - last > everyDays * Fsrs.DAY_MS
    }
}
