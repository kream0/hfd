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
        // A wrong word: "كفوا" said as "مثلا" (one letter off in a short word, كفرا, is taken
        // for the model mishearing, as it mostly is at the phone's level).
        t.feed("ولم يكن له مثلا أحد")
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
    fun scrapsOfWordsDoNotMove() {
        // What the phone's faint microphone gave before the level was raised (on al-Baqara 1–5,
        // whose disconnected letters now take any speech: see below), on ordinary words.
        val t = Tracker(listOf(target(112, 1), target(112, 2)))
        for (scrap in listOf("فرق", "في", "منذر")) assertEquals(scrap, 0, t.feed(scrap))
        assertEquals(0, t.position)
        // The words themselves do.
        assertEquals(4, t.feed("قل هو الله احد"))
    }

    @Test
    fun faintSpeechIsBroughtToANormalLevel() {
        // A voice at −50 dBFS (amplitude 0.0045 peak), as the diagnostics showed.
        val quiet = FloatArray(16_000) { (0.0045 * kotlin.math.sin(it * 0.05)).toFloat() }
        val (loud, gain) = Level.normalize(quiet)
        assertTrue(gain > 20f)
        assertTrue(loud.maxOf { kotlin.math.abs(it) } <= 0.98f)
        // Already loud speech is left as it is; silence too.
        assertEquals(1f, Level.normalize(FloatArray(1_000) { if (it % 2 == 0) 0.5f else -0.5f }).second)
        assertEquals(1f, Level.normalize(FloatArray(1_000)).second)
    }

    @Test
    fun audioStatsFindWhereTheSoundIs() {
        // A 1.5 kHz tone at −20 dB: its energy in the 1–2 kHz band, no clipping, ~3000 crossings/s.
        val tone = FloatArray(16_000) { (0.1 * kotlin.math.sin(2 * Math.PI * 1_500 * it / 16_000)).toFloat() }
        val s = AudioStats.of(tone)
        assertTrue(s.bands.toString(), s.bands[2] > 90)
        assertEquals(0f, s.clipped)
        assertTrue(s.zcr in 2_900f..3_100f)
        assertTrue(s.peakDb in -21f..-19f)
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

    @Test
    fun disconnectedLettersCountAsRecitedWhateverTheModelMadeOfThem() {
        // Āl ʿImrān 1–2: الٓمٓ, then ٱللَّهُ لَآ إِلَٰهَ إِلَّا هُوَ ٱلْحَىُّ ٱلْقَيُّومُ
        val t = Tracker(listOf(target(3, 1), target(3, 2)))
        // The long held "Alif Lām Mīm", heard as a made-up word.
        assertEquals(1, t.feed("منذر"))
        assertEquals(WordStatus.OK, t.statusOf(0, 0))
        assertEquals(7, t.feed("الله لا اله الا هو الحي القيوم"))
        assertTrue(t.done)
        // Heard together with the words after them.
        val u = Tracker(listOf(target(3, 1), target(3, 2)))
        assertEquals(8, u.feed("فرق الله لا اله الا هو الحي القيوم"))
        assertTrue((0 until 7).all { u.statusOf(1, it) == WordStatus.OK })
        // Other words still need to be heard.
        assertEquals(0, u.feed("منذر"))
    }

    @Test
    fun wordsSaidAgainBeforeGoingOnChangeNothing() {
        val t = Tracker(listOf(target(112, 1), target(112, 2), target(112, 3), target(112, 4)))
        assertEquals(4, t.feed("قل هو الله احد"))
        // The reciter says the end of the āya again, then goes on.
        assertEquals(2, t.feed("الله احد الله الصمد"))
        assertTrue((0 until 4).all { t.statusOf(0, it) == WordStatus.OK })
        assertTrue((0 until 2).all { t.statusOf(1, it) == WordStatus.OK })
        // Only words already recited: nothing moves.
        assertEquals(0, t.feed("الله الصمد"))
        assertEquals(6, t.position)
        // Words that are both said again and next go on (al-Fātiḥa 3 after the basmala and 2).
        val f = Tracker(listOf(target(1, 1), target(1, 2), target(1, 3)))
        f.feed("بسم الله الرحمن الرحيم")
        f.feed("الحمد لله رب العالمين")
        assertEquals(2, f.feed("الرحمن الرحيم"))
        assertTrue(f.done)
    }
}
