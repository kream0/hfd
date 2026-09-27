package app.hfd.core.srs

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.random.Random

@Serializable
enum class Rating(val value: Int) {
    @SerialName("again") AGAIN(1),
    @SerialName("hard") HARD(2),
    @SerialName("good") GOOD(3),
    @SerialName("easy") EASY(4),
}

@Serializable
enum class CardState {
    @SerialName("new") NEW,
    @SerialName("learning") LEARNING,
    @SerialName("review") REVIEW,
    @SerialName("relearning") RELEARNING,
}

/** Memorisation state of one āya. Times are epoch milliseconds (UTC). */
@Serializable
data class SrsCard(
    val state: CardState = CardState.NEW,
    /** Learning / relearning step, null in review. */
    val step: Int? = null,
    val stability: Double? = null,
    val difficulty: Double? = null,
    val due: Long = 0,
    val lastReview: Long? = null,
    val reps: Int = 0,
    val lapses: Int = 0,
    val lastRating: Rating? = null,
)

/**
 * FSRS-6 (Free Spaced Repetition Scheduler), a line-by-line port of the reference
 * implementation py-fsrs 6.3.2 with its default parameters, learning steps (1 min, 10 min) and
 * relearning step (10 min). Fuzz is seeded, so replaying the review log gives the same dates.
 */
class Fsrs(
    private val w: DoubleArray = DEFAULT_PARAMETERS,
    val desiredRetention: Double = 0.9,
    private val learningSteps: List<Long> = listOf(60_000L, 600_000L),
    private val relearningSteps: List<Long> = listOf(600_000L),
    private val maximumInterval: Int = 36_500,
    private val fuzz: Boolean = true,
) {
    init {
        require(w.size == 21) { "FSRS-6 has 21 parameters" }
    }

    private val decay = -w[20]
    private val factor = 0.9.pow(1 / decay) - 1

    /** Probability of recalling the card at [nowMs], counting whole elapsed days (as FSRS schedules). */
    fun retrievability(card: SrsCard, nowMs: Long): Double {
        val last = card.lastReview ?: return 0.0
        val s = card.stability ?: return 0.0
        val elapsedDays = max(0L, floorDiv(nowMs - last, DAY_MS))
        return (1 + factor * elapsedDays / s).pow(decay)
    }

    /** Same, with fractional days: a smooth "strength" for display. */
    fun strength(card: SrsCard, nowMs: Long): Double {
        val last = card.lastReview ?: return 0.0
        val s = card.stability ?: return 0.0
        val elapsedDays = max(0.0, (nowMs - last).toDouble() / DAY_MS)
        return (1 + factor * elapsedDays / s).pow(decay)
    }

    fun review(card: SrsCard, rating: Rating, nowMs: Long, fuzzSeed: Long = nowMs): SrsCard {
        val daysSinceLast = card.lastReview?.let { floorDiv(nowMs - it, DAY_MS) }
        var state = if (card.state == CardState.NEW) CardState.LEARNING else card.state
        var step = if (card.state == CardState.NEW) 0 else card.step
        var s = card.stability
        var d = card.difficulty
        val nextIntervalMs: Long

        fun updateStabilityAndDifficulty(firstReview: Boolean) {
            if (firstReview) {
                s = initialStability(rating)
                d = initialDifficulty(rating, clamp = true)
            } else if (daysSinceLast != null && daysSinceLast < 1) {
                s = shortTermStability(s!!, rating)
                d = nextDifficulty(d!!, rating)
            } else {
                s = nextStability(d!!, s!!, retrievability(card, nowMs), rating)
                d = nextDifficulty(d!!, rating)
            }
        }

        fun toReview(): Long {
            state = CardState.REVIEW
            step = null
            return intervalDays(s!!) * DAY_MS
        }

        fun stepped(steps: List<Long>): Long {
            val st = step!!
            if (steps.isEmpty() || (st >= steps.size && rating != Rating.AGAIN)) return toReview()
            return when (rating) {
                Rating.AGAIN -> {
                    step = 0
                    steps[0]
                }
                Rating.HARD -> when {
                    st == 0 && steps.size == 1 -> (steps[0] * 1.5).toLong()
                    st == 0 && steps.size >= 2 -> (steps[0] + steps[1]) / 2
                    else -> steps[st]
                }
                Rating.GOOD -> if (st + 1 == steps.size) toReview() else {
                    step = st + 1
                    steps[st + 1]
                }
                Rating.EASY -> toReview()
            }
        }

        nextIntervalMs = when (state) {
            CardState.LEARNING -> {
                updateStabilityAndDifficulty(firstReview = s == null || d == null)
                stepped(learningSteps)
            }
            CardState.REVIEW -> {
                if (daysSinceLast != null && daysSinceLast < 1) {
                    s = shortTermStability(s!!, rating)
                } else {
                    s = nextStability(d!!, s!!, retrievability(card, nowMs), rating)
                }
                d = nextDifficulty(d!!, rating)
                if (rating == Rating.AGAIN) {
                    if (relearningSteps.isEmpty()) {
                        intervalDays(s!!) * DAY_MS
                    } else {
                        state = CardState.RELEARNING
                        step = 0
                        relearningSteps[0]
                    }
                } else {
                    intervalDays(s!!) * DAY_MS
                }
            }
            CardState.RELEARNING -> {
                updateStabilityAndDifficulty(firstReview = false)
                stepped(relearningSteps)
            }
            CardState.NEW -> error("unreachable")
        }

        val interval = if (fuzz && state == CardState.REVIEW) fuzzed(nextIntervalMs, fuzzSeed) else nextIntervalMs
        val lapse = card.state == CardState.REVIEW && rating == Rating.AGAIN
        return SrsCard(
            state = state,
            step = step,
            stability = s,
            difficulty = d,
            due = nowMs + interval,
            lastReview = nowMs,
            reps = card.reps + 1,
            lapses = card.lapses + if (lapse) 1 else 0,
            lastRating = rating,
        )
    }

    // ------------------------------------------------------------------ formulas

    private fun clampD(d: Double) = min(max(d, MIN_DIFFICULTY), MAX_DIFFICULTY)
    private fun clampS(s: Double) = max(s, STABILITY_MIN)

    private fun initialStability(r: Rating) = clampS(w[r.value - 1])

    private fun initialDifficulty(r: Rating, clamp: Boolean): Double {
        val d = w[4] - exp(w[5] * (r.value - 1)) + 1
        return if (clamp) clampD(d) else d
    }

    fun intervalDays(stability: Double): Long {
        val raw = (stability / factor) * (desiredRetention.pow(1 / decay) - 1)
        return round(raw).toLong().coerceAtLeast(1).coerceAtMost(maximumInterval.toLong())
    }

    private fun shortTermStability(s: Double, r: Rating): Double {
        var inc = exp(w[17] * (r.value - 3 + w[18])) * s.pow(-w[19])
        if (r != Rating.AGAIN) inc = max(inc, 1.0)
        return clampS(s * inc)
    }

    private fun nextDifficulty(d: Double, r: Rating): Double {
        val easy = initialDifficulty(Rating.EASY, clamp = false)
        val delta = -(w[6] * (r.value - 3))
        val damped = d + (10.0 - d) * delta / 9.0
        return clampD(w[7] * easy + (1 - w[7]) * damped)
    }

    private fun nextStability(d: Double, s: Double, r: Double, rating: Rating): Double = clampS(
        if (rating == Rating.AGAIN) {
            val longTerm = w[11] * d.pow(-w[12]) * ((s + 1).pow(w[13]) - 1) * exp((1 - r) * w[14])
            val shortTerm = s / exp(w[17] * w[18])
            min(longTerm, shortTerm)
        } else {
            val hard = if (rating == Rating.HARD) w[15] else 1.0
            val easy = if (rating == Rating.EASY) w[16] else 1.0
            s * (1 + exp(w[8]) * (11 - d) * s.pow(-w[9]) * (exp((1 - r) * w[10]) - 1) * hard * easy)
        },
    )

    private fun fuzzed(intervalMs: Long, seed: Long): Long {
        val days = floorDiv(intervalMs, DAY_MS)
        if (days < 2.5) return intervalMs
        var delta = 1.0
        for ((start, end, f) in FUZZ_RANGES) delta += f * max(min(days.toDouble(), end) - start, 0.0)
        var lo = round(days - delta).toLong()
        var hi = round(days + delta).toLong()
        lo = max(2, lo)
        hi = min(hi, maximumInterval.toLong())
        lo = min(lo, hi)
        val r = Random(seed).nextDouble()
        val fuzzedDays = min(round(r * (hi - lo + 1) + lo).toLong(), maximumInterval.toLong())
        return fuzzedDays * DAY_MS
    }

    private fun floorDiv(a: Long, b: Long): Long = floor(a.toDouble() / b).toLong()

    companion object {
        const val DAY_MS = 86_400_000L
        const val STABILITY_MIN = 0.001
        const val MIN_DIFFICULTY = 1.0
        const val MAX_DIFFICULTY = 10.0

        val DEFAULT_PARAMETERS = doubleArrayOf(
            0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001, 1.8722, 0.1666, 0.796,
            1.4835, 0.0614, 0.2629, 1.6483, 0.6014, 1.8729, 0.5425, 0.0912, 0.0658, 0.1542,
        )

        private val FUZZ_RANGES = listOf(
            Triple(2.5, 7.0, 0.15),
            Triple(7.0, 20.0, 0.1),
            Triple(20.0, Double.POSITIVE_INFINITY, 0.05),
        )
    }
}
