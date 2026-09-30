package app.hfd.core.recite

/**
 * Arabic text reduced to what matters when comparing a recitation (as heard by speech
 * recognition, in ordinary spelling) with the Uthmani text: its letter skeleton.
 */
object Arabic {
    private fun isMark(c: Char): Boolean =
        c in 'ً'..'ٟ' || // ḥarakāt, shadda, sukūn, madda, hamza marks
            c == 'ٰ' || //        small (dagger) alif
            c in 'ۖ'..'ۭ' || // Qur'anic annotation signs (waqf, small letters, ۞ …)
            c == 'ـ' //           tatweel

    /**
     * No vowel signs, no alif in any form and no lone hamza (the Uthmani script writes many long
     * vowels differently: خَٰلِدُونَ / خالدون, ءَامَنُوا۟ / آمنوا), one yāʾ, tāʾ marbūṭa as hāʾ.
     */
    fun skeleton(s: String): String = buildString {
        for (c in s) {
            if (isMark(c)) continue
            when (c) {
                'ا', 'ٱ', 'أ', 'إ', 'آ', 'ء' -> Unit
                'ؤ' -> append('و')
                'ئ', 'ي', 'ى', 'ی' -> append('ى')
                'ة' -> append('ه')
                'ک' -> append('ك')
                else -> if (c in 'ء'..'ي' || c in 'ٱ'..'ۓ') append(c)
            }
        }
    }

    /**
     * Uthmani text in ordinary spelling, as the recogniser writes it: ٱ and the small alif as ا, no
     * Qur'anic annotation signs or tatweel (to prime the recogniser with what came before).
     */
    fun plain(s: String): String = buildString {
        for (c in s) {
            when {
                c == 'ٱ' || c == 'ٰ' -> append('ا')
                c in 'ۖ'..'ۭ' || c == 'ـ' -> Unit
                else -> append(c)
            }
        }
    }.split(' ').filter { it.isNotBlank() }.joinToString(" ")

    /** The words of an āya as written, without the pause and section signs between them. */
    fun words(text: String): List<String> = text.split(' ').filter { skeleton(it).isNotEmpty() }
}
