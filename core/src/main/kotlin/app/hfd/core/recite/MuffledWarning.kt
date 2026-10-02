package app.hfd.core.recite

/**
 * When to point out a muffled microphone: the voice is muffled ([Clarity.isMuffled]) and the
 * text hardly follows, under a word for every [SECONDS_PER_WORD] seconds of the last utterances
 * ([WINDOW_SECONDS], [MIN_SECONDS] at least). A muffled voice alone isn't enough: the owner's phone
 * microphone always sounds muffled (about 1 % of the energy above 1 kHz), and recognition guided
 * by the passage reads it well (2 Oct, 3:1–3:9 at 0.9 words a second), so the warning was a false
 * alarm there. A phone in a pocket (Recite bench) follows a few words, guided, in half a minute.
 */
class MuffledWarning {
    /** The last whole utterances: seconds, and words the text moved on by. */
    private val recent = ArrayDeque<Pair<Float, Int>>()

    /** Whether the warning shows. */
    var shown = false
        private set

    /** A whole utterance of [seconds], [muffled] or not, that moved the text on by [moved] words. */
    fun heard(muffled: Boolean, seconds: Float, moved: Int): Boolean {
        recent.addLast(seconds to maxOf(0, moved))
        while (recent.size > 1 && recent.sumOf { it.first.toDouble() } - recent.first().first >= WINDOW_SECONDS) recent.removeFirst()
        val total = recent.sumOf { it.first.toDouble() }
        val words = recent.sumOf { it.second }
        shown = muffled && total >= MIN_SECONDS && words * SECONDS_PER_WORD < total
        return shown
    }

    companion object {
        const val WINDOW_SECONDS = 30.0
        const val MIN_SECONDS = 12.0
        const val SECONDS_PER_WORD = 4.0
    }
}
