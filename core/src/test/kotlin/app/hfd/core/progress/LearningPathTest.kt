package app.hfd.core.progress

import app.hfd.core.Assets
import app.hfd.core.recite.Arabic
import app.hfd.core.srs.Rating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class LearningPathTest {
    private val all = Assets.fadail.fadail
    private val words = { ref: app.hfd.core.quran.AyahRef -> Arabic.words(Assets.quran.text(ref).orEmpty()).size }

    private fun learnt(vararg keys: String): ProgressState {
        val book = ProgressBook(zone = ZoneOffset.UTC)
        keys.forEach { book.apply(Event.Rate(1_000, it, Rating.GOOD, Mode.LEARN)) }
        return book.snapshot()
    }

    @Test
    fun startsWithTheShortestPassages() {
        val next = LearningPath.next(all, ProgressState(), words, limit = 5).map { it.id }
        assertEquals(5, next.size)
        // Al-Kawthar is the shortest sūra; nothing long comes early.
        assertEquals("kawthar", next.first())
        assertTrue(next.none { id -> all.first { it.id == id }.isLong })
        val lengths = next.map { id -> all.first { it.id == id }.ayat.sumOf(words) }
        assertEquals(lengths.sorted(), lengths)
    }

    @Test
    fun theClosingIsLearntThroughItsParts() {
        val everything = LearningPath.next(all, ProgressState(), words, limit = 100).map { it.id }
        assertTrue("closing" !in everything)
        assertTrue("fatiha" in everything && "baqara-opening" in everything)
        // The end of al-Kahf stays a passage of its own, and al-Kahf in full too.
        assertTrue("kahf-end" in everything && "kahf" in everything)
        assertEquals(all.size - 1, everything.size)
    }

    @Test
    fun begunPassagesContinueAndLeaveTheNewOnes() {
        val state = learnt("112:1", "112:2", "108:1", "108:2", "108:3")
        assertEquals(listOf("ikhlas"), LearningPath.inProgress(all, state).map { it.id })
        val next = LearningPath.next(all, state, words).map { it.id }
        assertTrue("ikhlas" !in next && "kawthar" !in next)
        // Al-Kahf in full is begun once its last four āyāt are.
        val kahf = learnt("18:107", "18:108", "18:109", "18:110")
        assertEquals(listOf("kahf"), LearningPath.inProgress(all, kahf).map { it.id })
    }
}
