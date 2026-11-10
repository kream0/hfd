package app.hfd.core.playback

import app.hfd.core.quran.AyahRef

/**
 * Listening to the whole Qur'an (the Qur'an section): a sūra at a time, each played through once
 * and followed by the next. A sūra's session has the id "sura:<n>", where a passage's has its
 * faḍīla's.
 */
object QuranListening {
    const val SURAS = 114
    private const val PREFIX = "sura:"

    fun id(sura: Int): String = PREFIX + sura

    /** The sūra a session id stands for; null for a passage's. */
    fun suraOf(id: String?): Int? =
        id?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.toIntOrNull()?.takeIf { it in 1..SURAS }

    /** The sūra that follows [sura]; none after an-Nās. */
    fun after(sura: Int): Int? = (sura + 1).takeIf { it in 1..SURAS }

    /** The sūra before [sura]; none before al-Fātiḥa. */
    fun before(sura: Int): Int? = (sura - 1).takeIf { it in 1..SURAS }

    fun ayat(sura: Int, count: Int): List<AyahRef> = (1..count).map { AyahRef(sura, it) }

    /**
     * The recitations that have every āya (everyayah.com's). A whole-sūra recitation
     * ([Reciter.timings]) is only cut into āyāt for the passages.
     */
    val RECITERS: List<Reciter> get() = Reciters.ALL.filter { it.timings == null }

    /** The Qur'an section's reciter: [chosen] there, else the passages' one ([passages]) if it has every āya. */
    fun reciter(chosen: String?, passages: String?): Reciter {
        val all = RECITERS
        return all.firstOrNull { it.id == chosen } ?: all.firstOrNull { it.id == passages } ?: all.first()
    }
}
