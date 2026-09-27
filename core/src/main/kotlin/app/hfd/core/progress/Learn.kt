package app.hfd.core.progress

import app.hfd.core.srs.Rating
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The steps of learning one āya (then, from the second one on, reciting from the start). */
@Serializable
enum class LearnStep {
    /** Listen ×N with the text (and translation). */
    @SerialName("listen") LISTEN,
    /** Listen and repeat aloud in the pauses. */
    @SerialName("repeat") REPEAT,
    /** Only the first word shows. */
    @SerialName("first_words") FIRST_WORDS,
    /** Recite from memory, text hidden. */
    @SerialName("recite") RECITE,
    /** Text revealed (and played): rate how it went. */
    @SerialName("revealed") REVEALED,
    /** Recite everything learnt so far from the start of the range (sabaq / sabqī). */
    @SerialName("cumulative") CUMULATIVE,
    @SerialName("cumulative_revealed") CUMULATIVE_REVEALED,
    @SerialName("done") DONE,
}

/**
 * Where a Learn session is: faḍīla [fadilaId], range [from]..[to] (positions in its āyāt), the
 * āya being learnt ([index]) and the step. Saved so a session resumes exactly there.
 */
@Serializable
data class LearnState(
    val fadilaId: String,
    val from: Int,
    val to: Int,
    val index: Int,
    val step: LearnStep = LearnStep.LISTEN,
    /** Times this āya went back to practice after "Again". */
    val attempt: Int = 0,
)

sealed interface LearnInput {
    /** Audio of the step finished, or "next". */
    data object Next : LearnInput
    data object Reveal : LearnInput
    data class Rated(val rating: Rating) : LearnInput
    data class ChainResult(val ok: Boolean) : LearnInput
    /** Skip this āya (e.g. already known). */
    data object Skip : LearnInput
}

object LearnFlow {
    /** Starts at the first āya of the range not learnt yet (or the range start if all are). */
    fun start(fadilaId: String, from: Int, to: Int, isNew: (Int) -> Boolean): LearnState {
        val first = (from..to).firstOrNull(isNew) ?: from
        return LearnState(fadilaId, from, to, first)
    }

    fun next(s: LearnState, input: LearnInput): LearnState = when (input) {
        LearnInput.Skip -> advance(s)
        LearnInput.Next -> when (s.step) {
            LearnStep.LISTEN -> s.copy(step = LearnStep.REPEAT)
            LearnStep.REPEAT -> s.copy(step = LearnStep.FIRST_WORDS)
            LearnStep.FIRST_WORDS -> s.copy(step = LearnStep.RECITE)
            else -> s
        }
        LearnInput.Reveal -> when (s.step) {
            LearnStep.RECITE, LearnStep.FIRST_WORDS -> s.copy(step = LearnStep.REVEALED)
            LearnStep.CUMULATIVE -> s.copy(step = LearnStep.CUMULATIVE_REVEALED)
            else -> s
        }
        is LearnInput.Rated -> if (s.step != LearnStep.REVEALED) s else when {
            // Not yet: practise it again (repeat in the pauses, then hide the text).
            input.rating == Rating.AGAIN -> s.copy(step = LearnStep.REPEAT, attempt = s.attempt + 1)
            s.index > s.from -> s.copy(step = LearnStep.CUMULATIVE)
            else -> advance(s)
        }
        is LearnInput.ChainResult -> if (s.step == LearnStep.CUMULATIVE_REVEALED) advance(s) else s
    }

    private fun advance(s: LearnState): LearnState =
        if (s.index >= s.to) s.copy(step = LearnStep.DONE, attempt = 0)
        else LearnState(s.fadilaId, s.from, s.to, s.index + 1)

    /** Steps shown as progress dots for one āya. */
    val MAIN_STEPS = listOf(LearnStep.LISTEN, LearnStep.REPEAT, LearnStep.FIRST_WORDS, LearnStep.RECITE, LearnStep.REVEALED)
}

/** Text helpers for hiding: the first real word of an āya (skipping marks like ۞). */
object Hiding {
    private fun isLetter(c: Char) = c in 'ء'..'ي' || c == 'ٱ'

    fun firstWord(text: String): String =
        text.split(' ').firstOrNull { w -> w.any(::isLetter) } ?: text.substringBefore(' ')
}
