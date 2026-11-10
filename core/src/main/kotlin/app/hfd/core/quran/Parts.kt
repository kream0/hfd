package app.hfd.core.quran

import kotlinx.serialization.Serializable

/** Where each juzʾ and each quarter of a ḥizb begins ([sūra, āya]), from Tanzil's quran-data.xml (tools/fetch-data.sh). */
@Serializable
data class PartsFile(val schema: Int = 1, val source: String = "", val juz: List<List<Int>>, val quarters: List<List<Int>>)

/**
 * The muṣḥaf's division for reading: 30 ajzāʾ, each of two aḥzāb (60), each of four quarters
 * (240). A part runs from its first āya to the one before the next part's.
 */
class Parts(file: PartsFile) {
    /** First āya of juzʾ 1…30. */
    val juz: List<AyahRef> = file.juz.map { AyahRef(it[0], it[1]) }
    /** First āya of each quarter of a ḥizb, 1…240. */
    val quarters: List<AyahRef> = file.quarters.map { AyahRef(it[0], it[1]) }
    /** First āya of ḥizb 1…60. */
    val hizb: List<AyahRef> = quarters.filterIndexed { i, _ -> i % QUARTERS == 0 }

    init {
        require(juz.size == JUZ && quarters.size == JUZ * 2 * QUARTERS) { "${juz.size} ajzāʾ, ${quarters.size} quarters" }
        require(juz == hizb.filterIndexed { i, _ -> i % 2 == 0 }) { "a juzʾ is two aḥzāb" }
        require(quarters.zipWithNext().all { (a, b) -> a < b }) { "quarters out of order" }
    }

    private fun partOf(starts: List<AyahRef>, ref: AyahRef): Int {
        val i = starts.binarySearch(ref)
        return if (i >= 0) i + 1 else -i - 1
    }

    /** The juzʾ (1…30) āya [ref] is in. */
    fun juzOf(ref: AyahRef): Int = partOf(juz, ref)

    /** The ḥizb (1…60) āya [ref] is in. */
    fun hizbOf(ref: AyahRef): Int = partOf(hizb, ref)

    /** The juzʾ that begins at [ref], if one does. */
    fun juzAt(ref: AyahRef): Int? = juz.binarySearch(ref).takeIf { it >= 0 }?.plus(1)

    /** The ḥizb that begins at [ref], if one does. */
    fun hizbAt(ref: AyahRef): Int? = hizb.binarySearch(ref).takeIf { it >= 0 }?.plus(1)

    companion object {
        const val JUZ = 30
        const val HIZB = 60
        const val QUARTERS = 4
    }
}
