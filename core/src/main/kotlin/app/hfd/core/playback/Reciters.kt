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
        Reciter("maher", "MaherAlMuaiqly128kbps", "Māhir al-Muʿayqilī", "ماهر المعيقلي", "Murattal", "Muʿayqilī"),
        Reciter("alafasy", "Alafasy_128kbps", "Mishary Rashid Alafasy", "مشاري راشد العفاسي", "Murattal", "Alafasy"),
        Reciter("husary", "Husary_128kbps", "Maḥmūd Khalīl al-Ḥuṣarī", "محمود خليل الحصري", "Murattal", "Ḥuṣarī"),
        Reciter("husary-muallim", "Husary_Muallim_128kbps", "Maḥmūd Khalīl al-Ḥuṣarī", "محمود خليل الحصري", "Muʿallim", "Ḥuṣarī · Muʿallim"),
        Reciter("minshawi", "Minshawy_Murattal_128kbps", "Muḥammad Ṣiddīq al-Minshāwī", "محمد صديق المنشاوي", "Murattal", "Minshāwī"),
        Reciter("abdulbasit", "Abdul_Basit_Murattal_192kbps", "ʿAbd al-Bāsiṭ ʿAbd aṣ-Ṣamad", "عبد الباسط عبد الصمد", "Murattal", "ʿAbd al-Bāsiṭ"),
        Reciter("dossary", "Yasser_Ad-Dussary_128kbps", "Yāsir ad-Dawsarī", "ياسر الدوسري", "Murattal", "Dawsarī"),
        Reciter("sudais", "Abdurrahmaan_As-Sudais_192kbps", "ʿAbd ar-Raḥmān as-Sudays", "عبد الرحمن السديس", "Murattal", "Sudays"),
        Reciter("shuraim", "Saood_ash-Shuraym_128kbps", "Saʿūd ash-Shuraym", "سعود الشريم", "Murattal", "Shuraym"),
        Reciter("qatami", "Nasser_Alqatami_128kbps", "Nāṣir al-Qaṭāmī", "ناصر القطامي", "Murattal", "Qaṭāmī"),
        Reciter("shatri", "Abu_Bakr_Ash-Shaatree_128kbps", "Abū Bakr ash-Shāṭirī", "أبو بكر الشاطري", "Murattal", "Shāṭirī"),
        Reciter("ajamy", "Ahmed_ibn_Ali_al-Ajamy_128kbps_ketaballah.net", "Aḥmad ibn ʿAlī al-ʿAjamī", "أحمد بن علي العجمي", "Murattal", "ʿAjamī"),
        Reciter("hudhaify", "Hudhaify_128kbps", "ʿAlī al-Ḥudhayfī", "علي الحذيفي", "Murattal", "Ḥudhayfī"),
        Reciter("budair", "Salah_Al_Budair_128kbps", "Ṣalāḥ al-Budayr", "صلاح البدير", "Murattal", "Budayr"),
        Reciter("ayyoub", "Muhammad_Ayyoub_128kbps", "Muḥammad Ayyūb", "محمد أيوب", "Murattal", "Ayyūb"),
        Reciter("rifai", "Hani_Rifai_192kbps", "Hānī ar-Rifāʿī", "هاني الرفاعي", "Murattal", "Rifāʿī"),
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
