package app.hfd.core.progress

import app.hfd.core.srs.CardState
import app.hfd.core.srs.Fsrs
import app.hfd.core.srs.SrsCard
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId

/** Everything known about one āya. */
@Serializable
data class AyahProgress(
    /** Recitations heard to at least 90 %. */
    val listens: Int = 0,
    val listenMs: Long = 0,
    val lastHeard: Long? = null,
    val card: SrsCard = SrsCard(),
    /** Chains (cumulative recitations) this āya was part of, and how many went right. */
    val chains: Int = 0,
    val chainsOk: Int = 0,
    /** Recitations checked by speech recognition, words recited in them, and mistakes. */
    val recites: Int = 0,
    val recitedWords: Int = 0,
    val recitedMistakes: Int = 0,
    val lastRecited: Long? = null,
    /** Word index (in the āya) → times it went wrong: the words to work on. */
    val weakWords: Map<Int, Int> = emptyMap(),
) {
    /** Share of words right over all checked recitations; null before the first one. */
    val reciteAccuracy: Float? get() = if (recitedWords == 0) null else (recitedWords - recitedMistakes).toFloat() / recitedWords
    val started: Boolean get() = card.state != CardState.NEW
    /** Graduated to review: learnt, now kept by spaced repetition. */
    val memorised: Boolean get() = card.state == CardState.REVIEW
}

/** Activity of one local day. */
@Serializable
data class DayStats(
    val listenMs: Long = 0,
    val listens: Int = 0,
    val ratings: Int = 0,
    /** Time spent reciting from memory (learn / review / test). */
    val reciteMs: Long = 0,
    /** Āyāt rated for the first time that day. */
    val learned: Int = 0,
) {
    val practiceMs: Long get() = listenMs + reciteMs
}

/** State rebuilt from the event log; also saved as a snapshot for fast startup. */
@Serializable
data class ProgressState(
    val ayat: Map<String, AyahProgress> = emptyMap(),
    /** Epoch day (local) → activity. */
    val days: Map<Long, DayStats> = emptyMap(),
    /** Faḍīla id → time of its last full test. */
    val tests: Map<String, Long> = emptyMap(),
    val events: Int = 0,
) {
    operator fun get(key: String): AyahProgress = ayat[key] ?: AyahProgress()
}

/**
 * Applies events to a mutable copy of the state. Replaying the whole log from empty gives the
 * same result as applying events as they happen.
 */
class ProgressBook(
    initial: ProgressState = ProgressState(),
    private val fsrs: Fsrs = Fsrs(),
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val ayat = HashMap(initial.ayat)
    private val days = HashMap(initial.days)
    private val tests = HashMap(initial.tests)
    private var events = initial.events

    fun apply(e: Event) {
        events++
        val day = Instant.ofEpochMilli(e.at).atZone(zone).toLocalDate().toEpochDay()
        val d = days[day] ?: DayStats()
        when (e) {
            is Event.Listen -> {
                val p = ayat[e.k] ?: AyahProgress()
                ayat[e.k] = p.copy(
                    listens = p.listens + e.n,
                    listenMs = p.listenMs + e.ms,
                    lastHeard = maxOf(p.lastHeard ?: 0, e.at),
                )
                days[day] = d.copy(listenMs = d.listenMs + e.ms, listens = d.listens + e.n)
            }
            is Event.Rate -> {
                val p = ayat[e.k] ?: AyahProgress()
                val first = !p.started
                // The seed makes fuzz reproducible, so a replay lands on the same due dates.
                val card = fsrs.review(p.card, e.rating, e.at, fuzzSeed = e.at xor e.k.hashCode().toLong())
                ayat[e.k] = p.copy(card = card)
                days[day] = d.copy(
                    ratings = d.ratings + 1,
                    reciteMs = d.reciteMs + e.ms.coerceIn(0, MAX_RECITE_MS),
                    learned = d.learned + if (first) 1 else 0,
                )
            }
            is Event.Recite -> {
                val p = ayat[e.k] ?: AyahProgress()
                val first = !p.started
                val card = fsrs.review(p.card, e.rating, e.at, fuzzSeed = e.at xor e.k.hashCode().toLong())
                val weak = HashMap(p.weakWords)
                for (w in e.miss) weak[w] = (weak[w] ?: 0) + 1
                ayat[e.k] = p.copy(
                    card = card,
                    recites = p.recites + 1,
                    recitedWords = p.recitedWords + e.n,
                    recitedMistakes = p.recitedMistakes + e.miss.size,
                    lastRecited = maxOf(p.lastRecited ?: 0, e.at),
                    weakWords = weak,
                )
                days[day] = d.copy(
                    ratings = d.ratings + 1,
                    reciteMs = d.reciteMs + e.ms.coerceIn(0, MAX_RECITE_MS),
                    learned = d.learned + if (first) 1 else 0,
                )
            }
            is Event.Chain -> {
                for (k in e.keys) {
                    val p = ayat[k] ?: AyahProgress()
                    ayat[k] = p.copy(chains = p.chains + 1, chainsOk = p.chainsOk + if (e.ok) 1 else 0)
                }
                days[day] = d.copy(reciteMs = d.reciteMs + e.ms.coerceIn(0, MAX_RECITE_MS * e.keys.size))
            }
            is Event.Test -> tests[e.f] = maxOf(tests[e.f] ?: 0, e.at)
        }
    }

    fun snapshot(): ProgressState = ProgressState(HashMap(ayat), HashMap(days), HashMap(tests), events)

    companion object {
        /** Time on one card counts up to this much (a phone left open doesn't inflate stats). */
        const val MAX_RECITE_MS = 3 * 60_000L

        fun replay(events: Sequence<Event>, fsrs: Fsrs = Fsrs(), zone: ZoneId = ZoneId.systemDefault()): ProgressState {
            val book = ProgressBook(fsrs = fsrs, zone = zone)
            events.forEach(book::apply)
            return book.snapshot()
        }
    }
}
