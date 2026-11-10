package app.hfd.core.quran

/**
 * The sūras' names in the transliteration the passages' titles use (Al-Fātiḥa, Āl ʿImrān), in
 * place of Tanzil's plain-letter one (Al-Faatiha, Aal-i-Imraan). Names only: the text is Tanzil's.
 */
object SuraNames {
    private val NAMES = listOf(
        "Al-Fātiḥa", "Al-Baqara", "Āl ʿImrān", "An-Nisāʾ", "Al-Māʾida", "Al-Anʿām", "Al-Aʿrāf", "Al-Anfāl", "At-Tawba", "Yūnus",
        "Hūd", "Yūsuf", "Ar-Raʿd", "Ibrāhīm", "Al-Ḥijr", "An-Naḥl", "Al-Isrāʾ", "Al-Kahf", "Maryam", "Ṭā-Hā",
        "Al-Anbiyāʾ", "Al-Ḥajj", "Al-Muʾminūn", "An-Nūr", "Al-Furqān", "Ash-Shuʿarāʾ", "An-Naml", "Al-Qaṣaṣ", "Al-ʿAnkabūt", "Ar-Rūm",
        "Luqmān", "As-Sajda", "Al-Aḥzāb", "Sabaʾ", "Fāṭir", "Yā-Sīn", "Aṣ-Ṣāffāt", "Ṣād", "Az-Zumar", "Ghāfir",
        "Fuṣṣilat", "Ash-Shūrā", "Az-Zukhruf", "Ad-Dukhān", "Al-Jāthiya", "Al-Aḥqāf", "Muḥammad", "Al-Fatḥ", "Al-Ḥujurāt", "Qāf",
        "Adh-Dhāriyāt", "Aṭ-Ṭūr", "An-Najm", "Al-Qamar", "Ar-Raḥmān", "Al-Wāqiʿa", "Al-Ḥadīd", "Al-Mujādila", "Al-Ḥashr", "Al-Mumtaḥana",
        "Aṣ-Ṣaff", "Al-Jumuʿa", "Al-Munāfiqūn", "At-Taghābun", "Aṭ-Ṭalāq", "At-Taḥrīm", "Al-Mulk", "Al-Qalam", "Al-Ḥāqqa", "Al-Maʿārij",
        "Nūḥ", "Al-Jinn", "Al-Muzzammil", "Al-Muddaththir", "Al-Qiyāma", "Al-Insān", "Al-Mursalāt", "An-Nabaʾ", "An-Nāziʿāt", "ʿAbasa",
        "At-Takwīr", "Al-Infiṭār", "Al-Muṭaffifīn", "Al-Inshiqāq", "Al-Burūj", "Aṭ-Ṭāriq", "Al-Aʿlā", "Al-Ghāshiya", "Al-Fajr", "Al-Balad",
        "Ash-Shams", "Al-Layl", "Aḍ-Ḍuḥā", "Ash-Sharḥ", "At-Tīn", "Al-ʿAlaq", "Al-Qadr", "Al-Bayyina", "Az-Zalzala", "Al-ʿĀdiyāt",
        "Al-Qāriʿa", "At-Takāthur", "Al-ʿAṣr", "Al-Humaza", "Al-Fīl", "Quraysh", "Al-Māʿūn", "Al-Kawthar", "Al-Kāfirūn", "An-Naṣr",
        "Al-Masad", "Al-Ikhlāṣ", "Al-Falaq", "An-Nās",
    )

    /** Sūra [index]'s name (1-based); null out of the Qur'an. */
    fun of(index: Int): String? = NAMES.getOrNull(index - 1)

    /** [suras] with these names. */
    fun apply(suras: List<SuraMeta>): List<SuraMeta> = suras.map { s -> of(s.index)?.let { s.copy(tname = it) } ?: s }
}
