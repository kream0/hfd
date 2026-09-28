package app.hfd.core.recite

import app.hfd.core.Assets
import app.hfd.core.progress.Event
import app.hfd.core.progress.ProgressBook
import app.hfd.core.quran.AyahRef
import app.hfd.core.srs.CardState
import app.hfd.core.srs.Rating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class TrackerTest {
    private fun target(sura: Int, aya: Int) = AyahRef(sura, aya).let { ReciteTarget(it, Arabic.words(Assets.quran.text(it)!!)) }

    @Test
    fun uthmaniAndOrdinarySpellingShareTheirSkeleton() {
        assertEquals(Arabic.skeleton("الحمد"), Arabic.skeleton("ٱلْحَمْدُ"))
        assertEquals(Arabic.skeleton("خالدون"), Arabic.skeleton("خَٰلِدُونَ"))
        assertEquals(Arabic.skeleton("آمنوا"), Arabic.skeleton("ءَامَنُوا۟"))
        assertEquals(Arabic.skeleton("العالمين"), Arabic.skeleton("ٱلْعَٰلَمِينَ"))
        assertEquals(Arabic.skeleton("رحمة"), Arabic.skeleton("رَحْمَه"))
    }

    @Test
    fun pauseSignsAreNotWords() {
        // 2:255 has several waqf signs standing between words.
        val words = Arabic.words(Assets.quran.text(AyahRef(2, 255))!!)
        assertTrue(words.none { Arabic.skeleton(it).isEmpty() })
        assertEquals(Arabic.skeleton("الله"), Arabic.skeleton(words.first()))
    }

    @Test
    fun aCorrectRecitationGoesThroughEveryWord() {
        val t = Tracker(listOf(target(1, 2), target(1, 3), target(1, 4)))
        assertEquals(4, t.feed("الحمد لله رب العالمين"))
        assertEquals(2, t.feed("الرحمن الرحيم"))
        assertEquals(3, t.feed("مالك يوم الدين"))
        assertTrue(t.done)
        assertTrue(t.finished().all { it.mistakes.isEmpty() && it.rating == Rating.GOOD })
    }

    @Test
    fun chunksMayCrossAyahBoundariesAndSplitWordsDifferently() {
        // يَـٰٓأَيُّهَا is one word in the Uthmani text, two in ordinary spelling.
        val t = Tracker(listOf(target(109, 1), target(109, 2)))
        t.feed("قل يا أيها الكافرون لا أعبد")
        t.feed("ما تعبدون")
        assertTrue(t.done)
        assertTrue(t.finished().all { it.mistakes.isEmpty() })
    }

    @Test
    fun wrongAndSkippedWordsAreMarked() {
        val t = Tracker(listOf(target(112, 1), target(112, 2), target(112, 3), target(112, 4)))
        t.feed("قل هو الله أحد")
        t.feed("الله الصمد")
        // "لم يلد ولم يولد" with "ولم" skipped.
        t.feed("لم يلد يولد")
        val third = t.resultOf(2)
        assertEquals(listOf(2), third.mistakes)
        assertEquals(WordStatus.MISSED, t.statusOf(2, 2))
        // A wrong word: "كفوا" said as "كفرا".
        t.feed("ولم يكن له كفرا أحد")
        assertEquals(WordStatus.WRONG, t.statusOf(3, 3))
        assertEquals(Rating.HARD, t.resultOf(3).rating)
    }

    @Test
    fun unrelatedSpeechDoesNotMove() {
        val t = Tracker(listOf(target(112, 1)))
        assertEquals(0, t.feed("السلام عليكم ورحمة الله وبركاته"))
        assertEquals(0, t.position)
    }

    @Test
    fun aHintCountsAsAMistake() {
        val t = Tracker(listOf(target(112, 1)))
        t.feed("قل هو")
        assertEquals(Arabic.skeleton("الله"), Arabic.skeleton(t.hint()!!))
        t.feed("أحد")
        assertEquals(listOf(2), t.resultOf(0).mistakes)
    }

    @Test
    fun ratingFollowsMistakes() {
        assertEquals(Rating.GOOD, Tracker.ratingFor(10, 0))
        assertEquals(Rating.HARD, Tracker.ratingFor(10, 1))
        assertEquals(Rating.HARD, Tracker.ratingFor(40, 4))
        assertEquals(Rating.AGAIN, Tracker.ratingFor(10, 2))
    }

    @Test
    fun recitationsDriveTheCardAndTrackWeakWords() {
        val book = ProgressBook(zone = ZoneOffset.UTC)
        book.apply(Event.Recite(1_000, "112:3", n = 4, miss = listOf(2), rating = Rating.HARD, ms = 5_000))
        book.apply(Event.Recite(90_000_000, "112:3", n = 4, miss = listOf(2), rating = Rating.HARD, ms = 4_000))
        val p = book.snapshot()["112:3"]
        assertTrue(p.card.state != CardState.NEW)
        assertEquals(2, p.recites)
        assertEquals(mapOf(2 to 2), p.weakWords)
        assertEquals(0.75f, p.reciteAccuracy)
    }
}
