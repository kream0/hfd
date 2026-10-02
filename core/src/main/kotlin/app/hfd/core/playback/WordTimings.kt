package app.hfd.core.playback

import app.hfd.core.quran.AyahRef
import kotlinx.serialization.Serializable

/**
 * When each word of the passages starts in a recitation, for the reading view to light up the
 * word being recited while listening (assets/audio/words/<reciter id>.json, made by
 * tools/words/words.py from the audio exactly as the app plays it). The words are the āya's
 * text's (Arabic.words: its space-separated tokens that hold letters).
 */
@Serializable
data class WordTimings(
    val schema: Int,
    val reciter: String,
    /** "2:255" → when each word starts, in centiseconds from the start of the āya's audio. */
    val words: Map<String, List<Int>>,
    val note: String = "",
) {
    /** The word of [ref] being recited [positionMs] into its audio (the last one started), or null before the first. */
    fun wordAt(ref: AyahRef, positionMs: Long): Int? {
        val starts = words[ref.key] ?: return null
        val cs = positionMs / 10
        var at = -1
        for ((i, s) in starts.withIndex()) {
            if (s <= cs) at = i else break
        }
        return at.takeIf { it >= 0 }
    }
}
