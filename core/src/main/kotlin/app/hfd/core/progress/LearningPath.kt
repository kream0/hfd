package app.hfd.core.progress

import app.hfd.core.fadail.Fadila
import app.hfd.core.quran.AyahRef
import kotlinx.serialization.Serializable

/** The order the home screen offers the passages to learn in (a setting). */
@Serializable
enum class LearnOrder {
    /** Fewest words first: short sūras before long ones. */
    SHORTEST,
    /** The list's order, from al-Fātiḥa to an-Nās. */
    START,
    /** From an-Nās back to al-Fātiḥa, the way the short sūras are usually taught. */
    END,
}

/**
 * What to learn next, for the home screen: the passages begun and not finished, then the new
 * ones, in the chosen [LearnOrder]. A passage made only of shorter ones (the closing: al-Fātiḥa +
 * al-Baqara 1–5) is learnt through them.
 */
object LearningPath {
    /** Passages with some āyāt learnt and some still new. */
    fun inProgress(all: List<Fadila>, state: ProgressState, words: (AyahRef) -> Int, order: LearnOrder = LearnOrder.SHORTEST): List<Fadila> =
        sorted(units(all).filter { f -> f.ayat.count { state[it.key].started } in 1 until f.size }, words, order)

    /** Up to [limit] passages not begun yet ([words] per āya, for [LearnOrder.SHORTEST]). */
    fun next(all: List<Fadila>, state: ProgressState, words: (AyahRef) -> Int, order: LearnOrder = LearnOrder.SHORTEST, limit: Int = 3): List<Fadila> =
        sorted(units(all).filter { f -> f.ayat.none { state[it.key].started } }, words, order).take(limit)

    private fun sorted(list: List<Fadila>, words: (AyahRef) -> Int, order: LearnOrder): List<Fadila> = when (order) {
        LearnOrder.SHORTEST -> list.map { f -> f to f.ayat.sumOf(words) }.sortedBy { it.second }.map { it.first }
        LearnOrder.START -> list
        LearnOrder.END -> list.asReversed()
    }

    /** Without the passages whose every āya is in a shorter one. */
    private fun units(all: List<Fadila>): List<Fadila> {
        val sets = all.associateWith { it.ayat.toSet() }
        return all.filter { f ->
            f.ayat.any { ref -> all.none { g -> g.size < f.size && ref in sets.getValue(g) } }
        }
    }
}
