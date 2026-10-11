package app.hfd.core.recite

import app.hfd.core.Assets
import app.hfd.core.quran.AyahRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A partial reading ends where the reciter is still speaking: a word held long (a madd) is read
 * half said, and was marked wrong a moment before the next reading heard it whole (the owner,
 * 11 Oct, 1.15.2's readings every 0.3 s: red, then white). Its last words, unless right, wait.
 */
class FollowerEdgeTest {
    private fun targets(sura: Int, ayat: IntRange) = ayat.map { ReciteTarget(AyahRef(sura, it), Arabic.words(Assets.quran.text(AyahRef(sura, it)).orEmpty())) }

    private val fatiha = targets(1, 1..7)

    @Test
    fun aWordHalfSaidAtAPartialReadingsEndWaits() {
        val f = Follower(fatiha)
        // ٱلرَّحْمَٰنِ held long: read الرحار, a moment before the reading that hears it whole.
        f.heard(0, "بِسْمِ اللَّهِ الرَّحَّارِ", final = false)
        assertEquals(2, f.tracker.position)
        assertEquals(WordStatus.PENDING, f.tracker.status[2])
        f.heard(0, "بِسْمِ اللَّهِ الرَّحْمَنِ الرَّحِيمِ", final = false)
        assertEquals(4, f.tracker.position)
        assertTrue((0 until 4).all { f.tracker.status[it] == WordStatus.OK })
    }

    @Test
    fun aWholeUtterancesReadingDecidesItsLastWords() {
        val f = Follower(fatiha)
        f.heard(0, "بِسْمِ اللَّهِ الرَّحَّارِ", final = true)
        assertEquals(3, f.tracker.position)
        assertEquals(WordStatus.WRONG, f.tracker.status[2])
    }

    /** Words marked wrong or missed at some reading (as the app shows them) and right at the end. */
    private fun redThenRight(name: String, targets: List<ReciteTarget>): List<String> {
        val f = Follower(targets)
        val red = HashSet<Int>()
        // As the app: words right in a whole utterance's reading stay white (ReciteSession.wasRight).
        val confirmed = BooleanArray(f.tracker.size)
        for (line in javaClass.getResourceAsStream("/recite/$name")!!.bufferedReader().readLines()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val parts = line.split(" ", limit = 4)
            if (parts[1] == "session") continue
            val final = parts[1] == "final"
            f.heard(parts[2].toInt(), parts.getOrElse(3) { "" }, final)
            for (w in 0 until f.tracker.position) {
                if (final && f.tracker.status[w] == WordStatus.OK) confirmed[w] = true
                if (f.tracker.status[w] == WordStatus.WRONG && !confirmed[w]) red += w
            }
        }
        val words = targets.flatMap { it.words }
        assertTrue("$name: not followed to the end", f.tracker.done)
        assertTrue("$name: mistakes ${f.tracker.finished().filter { it.mistakes.isNotEmpty() }}", f.tracker.finished().all { it.mistakes.isEmpty() })
        return red.filter { f.tracker.status[it] == WordStatus.OK }.sorted().map { words[it] }
    }

    @Test
    fun theOwnersHeldWordsDontTurnRedFirst() {
        // Before: 13 words of al-Fātiḥa and 10 of 3:18-19 red, then white. The few left are
        // misread mid-reading (رب read رق), shown only after the utterance (ReciteSession.decided).
        val fatihaRed = redThenRight("owner-1.15.2-fatiha.log", fatiha)
        val shahidaRed = redThenRight("owner-1.15.2-shahida.log", targets(3, 18..19))
        assertTrue("al-Fātiḥa: $fatihaRed", fatihaRed.size <= 1)
        assertTrue("3:18-19: $shahidaRed", shahidaRed.size <= 2)
    }
}
