package app.hfd.core.fadail

import app.hfd.core.quran.AyahRef
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Text in the app's languages. Arabic is only set where the original is Arabic (titles). */
@Serializable
data class Localized(val ar: String? = null, val fr: String, val en: String) {
    fun pick(lang: String): String = when (lang) {
        "fr" -> fr
        "ar" -> ar ?: en
        else -> en
    }
}

@Serializable
data class AyahRange(val sura: Int, val from: Int, val to: Int) {
    val size: Int get() = to - from + 1
    fun ayat(): List<AyahRef> = (from..to).map { AyahRef(sura, it) }
    operator fun contains(ref: AyahRef): Boolean = ref.sura == sura && ref.aya in from..to
    override fun toString(): String = if (from == to) "$sura:$from" else "$sura:$from–$to"
}

@Serializable
data class Source(
    /** Book, e.g. "Ṣaḥīḥ al-Bukhārī", "Ṣaḥīḥ al-Jāmiʿ (al-Albānī)". */
    val collection: String,
    /** Number as the linked page shows it, e.g. "4474", "809a". */
    val number: String,
    /** Companion who narrates it. */
    val narrator: String,
    /** Page where the reference can be read (sunnah.com or dorar.net). */
    val url: String,
)

@Serializable
enum class Grade {
    @SerialName("sahih") SAHIH,
    @SerialName("hasan") HASAN,
    @SerialName("daif") DAIF,
    @SerialName("mawdu") MAWDU;

    /** Ṣaḥīḥ and ḥasan are shown by default; weak and fabricated only behind the setting. */
    val acceptable: Boolean get() = this == SAHIH || this == HASAN
}

@Serializable
data class Grading(
    val grade: Grade,
    /** Who graded it, e.g. "al-Bukhārī (Ṣaḥīḥ)", "al-Albānī". */
    val by: String,
    /** Other gradings worth knowing, in a short sentence. */
    val note: Localized? = null,
)

@Serializable
enum class Occasion {
    @SerialName("morning") MORNING,
    @SerialName("evening") EVENING,
    @SerialName("night") NIGHT,
    @SerialName("before_sleep") BEFORE_SLEEP,
    @SerialName("after_prayer") AFTER_PRAYER,
    @SerialName("friday") FRIDAY,
    @SerialName("any") ANY,
}

/** A narration about a passage: what it says, where to read it, how it is graded. */
@Serializable
data class Virtue(
    /** Short, faithful paraphrase of what the ḥadīth says. */
    val text: Localized,
    val sources: List<Source>,
    val grading: Grading,
    /** When the narration says to recite the passage; drives home suggestions and reminders. */
    val occasions: List<Occasion> = emptyList(),
    /** Every reference was checked against the source text. Unverified narrations stay hidden. */
    val verified: Boolean = false,
) {
    /** Shown: verified, and either sound or the user asked to see weak narrations. */
    fun visible(showWeak: Boolean): Boolean = verified && (grading.grade.acceptable || showWeak)
}

/**
 * A passage of the reference list (the app "سور وآيات فاضلة", see fadail.json), in its order.
 * Every passage is listed; its narrations follow the grading rules of [Virtue.visible].
 */
@Serializable
data class Fadila(
    /** Stable slug; progress and resume state refer to it. */
    val id: String,
    val title: Localized,
    val ranges: List<AyahRange>,
    /**
     * Recitations in a row in the reference's daily wird (end of at-Tawba ×7…). Kept as the
     * reference gives it; the app is for learning, so neither playback nor the lists use it.
     */
    val times: Int = 1,
    /** Narrations about this passage; none when no specific one is known. */
    val virtues: List<Virtue> = emptyList(),
    val tags: List<String> = emptyList(),
) {
    val ayat: List<AyahRef> get() = ranges.flatMap { it.ayat() }
    val size: Int get() = ranges.sumOf { it.size }
    val isLong: Boolean get() = TAG_LONG in tags

    fun virtues(showWeak: Boolean): List<Virtue> = virtues.filter { it.visible(showWeak) }

    /** Weak narrations left out while the setting is off. */
    fun hiddenWeak(showWeak: Boolean): Int =
        if (showWeak) 0 else virtues.count { it.verified && !it.grading.grade.acceptable }

    /** When to recite it, from the narrations shown. */
    fun occasions(showWeak: Boolean): Set<Occasion> = virtues(showWeak).flatMapTo(LinkedHashSet()) { it.occasions }

    companion object {
        const val TAG_LONG = "long"
    }
}

@Serializable
data class FadailFile(
    val schema: Int,
    val fadail: List<Fadila>,
    /** Where the list of passages comes from. */
    val source: String = "",
) {
    companion object {
        const val SCHEMA = 2
    }
}
