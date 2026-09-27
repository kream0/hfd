package app.hfd.core.playback

import app.hfd.core.quran.AyahRef

/** A recitation on everyayah.com, one MP3 per āya. Folder names were checked on the server. */
data class Reciter(
    val id: String,
    /** Folder under https://everyayah.com/data/ */
    val folder: String,
    val name: String,
    val nameAr: String,
    /** Short description, e.g. "Murattal" or "Muʿallim (teaching)". */
    val style: String,
    /** For chips: "Alafasy", "Ḥuṣarī · Muʿallim"… */
    val short: String,
)

object Reciters {
    val ALL = listOf(
        Reciter("alafasy", "Alafasy_128kbps", "Mishary Rashid Alafasy", "مشاري راشد العفاسي", "Murattal", "Alafasy"),
        Reciter("husary", "Husary_128kbps", "Maḥmūd Khalīl al-Ḥuṣarī", "محمود خليل الحصري", "Murattal", "Ḥuṣarī"),
        Reciter("husary-muallim", "Husary_Muallim_128kbps", "Maḥmūd Khalīl al-Ḥuṣarī", "محمود خليل الحصري", "Muʿallim", "Ḥuṣarī · Muʿallim"),
        Reciter("minshawi", "Minshawy_Murattal_128kbps", "Muḥammad Ṣiddīq al-Minshāwī", "محمد صديق المنشاوي", "Murattal", "Minshāwī"),
        Reciter("abdulbasit", "Abdul_Basit_Murattal_192kbps", "ʿAbd al-Bāsiṭ ʿAbd aṣ-Ṣamad", "عبد الباسط عبد الصمد", "Murattal", "ʿAbd al-Bāsiṭ"),
    )
    val DEFAULT: Reciter = ALL.first()

    fun byId(id: String?): Reciter = ALL.firstOrNull { it.id == id } ?: DEFAULT
}

object EveryAyah {
    const val BASE = "https://everyayah.com/data/"

    /** "002255.mp3" for 2:255. */
    fun fileName(ref: AyahRef): String = "%03d%03d.mp3".format(ref.sura, ref.aya)

    fun url(reciter: Reciter, ref: AyahRef): String = BASE + reciter.folder + "/" + fileName(ref)

    /** The basmala as each reciter recites it: al-Fātiḥa's first āya (every folder has it). */
    val BASMALA = AyahRef(1, 1)
}

/** Length of gaps, and a guess of an āya's length until the real one is known. */
object Timing {
    /** Silence after a recitation lasting [durationMs]. */
    fun gapMs(durationMs: Long, factor: Float): Long = (durationMs * factor).toLong().coerceIn(0, 10 * 60_000)

    /**
     * Rough length of a recitation from its text (Arabic letters only), used for a gap before the
     * āya has been measured. Replaced by the real length as soon as the player knows it.
     */
    fun estimateMs(text: String, reciter: Reciter): Long {
        val letters = text.count { it in 'ء'..'ي' || it == 'ٱ' }
        val perLetter = if (reciter.style == "Muʿallim") 260 else 140
        return (letters * perLetter).toLong().coerceAtLeast(1_500)
    }
}
