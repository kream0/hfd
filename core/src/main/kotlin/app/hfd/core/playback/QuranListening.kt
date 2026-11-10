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

    /** The Qur'an section's reciter: the one [chosen] there, else the passages' ([passages]). */
    fun choice(chosen: String?, passages: String?): Reciter =
        Reciters.ALL.firstOrNull { it.id == chosen } ?: Reciters.byId(passages)

    /**
     * Who recites when the chosen recitation lacks a sūra: the first that has every āya
     * (everyayah.com's have).
     */
    val FALLBACK: Reciter get() = Reciters.ALL.first { it.timings == null }

    /**
     * Whether [reciter] has all of [ayat]. A whole-sūra recitation ([Reciter.timings], [timings]
     * its āya cuts) only has the āyāt cut so far: the passages', so some sūras whole (al-Kahf,
     * Yā-Sīn, al-Mulk…) and most not.
     */
    fun has(reciter: Reciter, timings: AyahTimings?, ayat: List<AyahRef>): Boolean =
        reciter.timings == null || timings != null && ayat.all { it in timings }

    /** Who recites [ayat] (a sūra) in the Qur'an section: [choice] if it [has] them, else the [FALLBACK]. */
    fun reciterFor(choice: Reciter, timings: AyahTimings?, ayat: List<AyahRef>): Reciter =
        if (has(choice, timings, ayat)) choice else FALLBACK
}
