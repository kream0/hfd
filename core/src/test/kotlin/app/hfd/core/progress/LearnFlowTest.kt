package app.hfd.core.progress

import app.hfd.core.srs.Rating
import org.junit.Assert.assertEquals
import org.junit.Test

class LearnFlowTest {
    private fun LearnState.go(vararg inputs: LearnInput): LearnState = inputs.fold(this) { s, i -> LearnFlow.next(s, i) }

    @Test
    fun firstAyaThenCumulativeRecitation() {
        var s = LearnFlow.start("kahf-first-ten", 0, 1) { true }
        assertEquals(0, s.index)
        assertEquals(LearnStep.LISTEN, s.step)
        s = s.go(LearnInput.Next)
        assertEquals(LearnStep.REPEAT, s.step)
        s = s.go(LearnInput.Next, LearnInput.Next)
        assertEquals(LearnStep.RECITE, s.step)
        s = s.go(LearnInput.Reveal)
        assertEquals(LearnStep.REVEALED, s.step)
        // First āya of the range: no cumulative step, on to the next āya.
        s = s.go(LearnInput.Rated(Rating.GOOD))
        assertEquals(LearnState("kahf-first-ten", 0, 1, 1, LearnStep.LISTEN), s)
        s = s.go(LearnInput.Next, LearnInput.Next, LearnInput.Next, LearnInput.Reveal, LearnInput.Rated(Rating.HARD))
        assertEquals(LearnStep.CUMULATIVE, s.step)
        s = s.go(LearnInput.Reveal)
        assertEquals(LearnStep.CUMULATIVE_REVEALED, s.step)
        s = s.go(LearnInput.ChainResult(ok = false))
        assertEquals(LearnStep.DONE, s.step)
    }

    @Test
    fun againGoesBackToPractice() {
        val s = LearnState("f", 0, 3, 2, LearnStep.REVEALED)
        val again = LearnFlow.next(s, LearnInput.Rated(Rating.AGAIN))
        assertEquals(LearnStep.REPEAT, again.step)
        assertEquals(1, again.attempt)
        assertEquals(2, again.index)
    }

    @Test
    fun inputsOutOfPlaceAreIgnored() {
        val s = LearnState("f", 0, 3, 0, LearnStep.LISTEN)
        assertEquals(s, LearnFlow.next(s, LearnInput.Rated(Rating.GOOD)))
        assertEquals(s, LearnFlow.next(s, LearnInput.ChainResult(true)))
        assertEquals(s, LearnFlow.next(s, LearnInput.Reveal))
    }

    @Test
    fun startsAtTheFirstNewAyaAndSkips() {
        val s = LearnFlow.start("f", 2, 6) { it >= 4 }
        assertEquals(4, s.index)
        assertEquals(5, LearnFlow.next(s, LearnInput.Skip).index)
        assertEquals(LearnStep.DONE, LearnFlow.next(s.copy(index = 6), LearnInput.Skip).step)
        // Everything learnt already: start over from the range start.
        assertEquals(2, LearnFlow.start("f", 2, 6) { false }.index)
    }

    @Test
    fun firstWordSkipsMarks() {
        assertEquals("إِنَّ", Hiding.firstWord("۞ إِنَّ ٱللَّهَ"))
        assertEquals("الٓمٓ", Hiding.firstWord("الٓمٓ"))
    }
}
