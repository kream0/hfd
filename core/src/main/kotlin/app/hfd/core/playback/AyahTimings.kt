package app.hfd.core.playback

import app.hfd.core.quran.AyahRef
import kotlinx.serialization.Serializable

/** One whole-sūra file of a recitation. */
@Serializable
data class SuraAudio(
    val file: String,
    /** Its size in bytes when the timings were made (a different size means a different file). */
    val size: Long,
    val seconds: Double = 0.0,
    val kbps: List<Int> = emptyList(),
)

/**
 * Where each āya of the passages is in a recitation published as whole-sūra MP3s (mp3quran.net):
 * byte ranges of the sūra files, found by tools/audio/align.py (`assets/audio/<reciter>.json`).
 * An āya is then fetched as one range request and played like an everyayah.com file.
 */
@Serializable
data class AyahTimings(
    val schema: Int,
    val reciter: String,
    val base: String,
    val suras: Map<String, SuraAudio>,
    /** "2:255" → [start, end) in bytes. */
    val ayat: Map<String, List<Long>>,
    val name: String = "",
    val nameAr: String = "",
    val note: String = "",
    /** Āyāt whose cut the check couldn't confirm. */
    val unchecked: List<String> = emptyList(),
) {
    fun sura(ref: AyahRef): SuraAudio? = suras[ref.sura.toString()]

    fun url(ref: AyahRef): String? = sura(ref)?.let { base + it.file }

    /** Bytes [first, last] (inclusive, as in a Range header) of [ref] in its sūra file. */
    fun bytes(ref: AyahRef): LongRange? = ayat[ref.key]?.takeIf { it.size == 2 && it[1] > it[0] }?.let { it[0] until it[1] }

    operator fun contains(ref: AyahRef): Boolean = bytes(ref) != null && url(ref) != null
}
