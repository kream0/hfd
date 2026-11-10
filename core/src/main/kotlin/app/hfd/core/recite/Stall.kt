package app.hfd.core.recite

/**
 * The recogniser stalls at a short pause: it reads an utterance up to the pause and then nothing
 * of what is said after it, reading after reading, until the utterance ends (then all at once, or
 * not at all: al-Fātiḥa's 1:5 said in the utterance of 1:3–4 was never read, and counted as
 * skipped; Muʿayqilī on the bench, 10 Nov). An utterance cut at every such pause reads a clear
 * voice better and a muffled one worse (its fragments are too short to make out). So only there:
 * when readings of an utterance stop moving the recitation while the voice goes on past a pause,
 * the utterance is ended at that pause ([Segmenter.splitAt]) and what follows starts a new one.
 * Not in use ([DEFAULT]).
 */
class Stall(
    /** Voice after the pause, with no reading moving the recitation, that makes it a stall (samples). */
    private val after: Int = DEFAULT,
) {
    private var id = -1
    private var position = -1
    /** The utterance's length when a reading of it last moved the recitation. */
    private var since = 0

    /**
     * A reading of [u] (not whole yet) left the recitation at [position]. Returns the sample to end
     * the utterance at, or null.
     */
    fun heard(u: Utterance, position: Int): Int? {
        if (u.final || after <= 0) return null
        if (u.id != id || position != this.position) {
            id = u.id
            this.position = position
            since = u.pcm.size
            return null
        }
        // The first pause from about where the readings stopped moving, with enough said after it.
        var i = 0
        while (i + 1 < u.pauses.size) {
            val stopped = u.pauses[i]
            val again = u.pauses[i + 1]
            if (stopped >= since - BEFORE && u.pcm.size - again >= after) return (stopped + again) / 2
            i += 2
        }
        return null
    }

    companion object {
        /** 1.2 s said after a pause and not read: words wait that long for nothing. */
        const val AFTER = (1.2 * Segmenter.RATE).toInt()
        /**
         * Off (0): on the whole bench (10 Nov, 58 cases, one run) it read as many words right as
         * without it (94.5 %), with fewer missed (19 → 6) and wrong (42 → 34) but more never
         * decided (52 → 72), twice the red flashes shown (9 → 19) and a swapped word less caught.
         */
        const val DEFAULT = 0
        /** The reading that last moved the recitation had heard up to its end: the pause is at most this before it. */
        private const val BEFORE = (0.8 * Segmenter.RATE).toInt()
    }
}
