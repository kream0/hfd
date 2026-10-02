package app.hfd.core.recite

/**
 * When to point out a muffled microphone: the voice is muffled ([Clarity.isMuffled]) and the
 * text doesn't follow, [AFTER] utterances in a row (of [MIN_SECONDS] or more) having moved it on
 * by nothing. A muffled voice alone isn't enough: the owner's phone microphone always sounds
 * muffled (about 1 % of the energy above 1 kHz), and recognition guided by the passage reads it
 * well (2 Oct, 3:2–3:6 word for word), so the warning was a false alarm there.
 */
class MuffledWarning {
    private var unfollowed = 0

    /** Whether the warning shows. */
    var shown = false
        private set

    /** A whole utterance of [seconds], [muffled] or not, that moved the text on by [moved] words. */
    fun heard(muffled: Boolean, seconds: Float, moved: Int): Boolean {
        if (moved > 0) unfollowed = 0 else if (seconds >= MIN_SECONDS) unfollowed++
        shown = muffled && unfollowed >= AFTER
        return shown
    }

    companion object {
        const val AFTER = 2
        const val MIN_SECONDS = 1.5f
    }
}
