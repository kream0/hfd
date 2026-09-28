package app.hfd.core.progress

import app.hfd.core.fadail.Fadila
import app.hfd.core.quran.AyahRef

/**
 * What to learn next, for the home screen: the passages begun and not finished, then the new
 * ones, shortest first (the usual way into memorising: short sūras before long ones). A passage
 * made only of shorter ones (the closing: al-Fātiḥa + al-Baqara 1–5) is learnt through them.
 */
object LearningPath {
    /** Passages with some āyāt learnt and some still new, in the list's order. */
    fun inProgress(all: List<Fadila>, state: ProgressState): List<Fadila> =
        units(all).filter { f -> f.ayat.count { state[it.key].started } in 1 until f.size }

    /** Up to [limit] passages not begun yet, fewest words first ([words] per āya). */
    fun next(all: List<Fadila>, state: ProgressState, words: (AyahRef) -> Int, limit: Int = 3): List<Fadila> =
        units(all).filter { f -> f.ayat.none { state[it.key].started } }
            .map { f -> f to f.ayat.sumOf(words) }
            .sortedBy { it.second }
            .take(limit)
            .map { it.first }

    /** Without the passages whose every āya is in a shorter one. */
    private fun units(all: List<Fadila>): List<Fadila> {
        val sets = all.associateWith { it.ayat.toSet() }
        return all.filter { f ->
            f.ayat.any { ref -> all.none { g -> g.size < f.size && ref in sets.getValue(g) } }
        }
    }
}
