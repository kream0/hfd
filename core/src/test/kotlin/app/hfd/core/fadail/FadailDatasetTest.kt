package app.hfd.core.fadail

import app.hfd.core.Assets
import app.hfd.core.quran.AyahRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FadailDatasetTest {
    /**
     * The passages of the reference app "سور وآيات فاضلة" (com.yassine.mob.ayatfadila), in its
     * order, as listed with the app's own recitation ("مصحف السور والآيات الفاضلة", Warsh, on
     * SoundCloud with the app's download links): الفاتحة، "ألم" البقرة إلى "المفلحون"، آية
     * الكرسي إلى "خالدون"، "آمن الرسول" إلى آخر السورة، …، الإخلاص 3 مرات، الفلق والناس، ثم
     * الختم بالفاتحة وأول البقرة إلى "المفلحون". The publisher's article (aljamaa.net) gives the
     * same list. Whole sūra = its number; "×n" = recitations. Al-Kahf in full comes from the
     * app's description (al-Kahf on Friday).
     */
    private val reference = listOf(
        "1:1-7",
        "2:1-5", "2:255-257", "2:285-286",
        "3:1-9", "3:18-19", "3:26-27", "3:190-200",
        "9:128-129 ×7",
        "18:107-110", "18",
        "32", "36", "40:1-3", "44", "48:29",
        "56", "57", "59", "61", "62", "64",
        "67", "87", "93", "94", "96", "97", "99 ×4",
        "102", "103 ×2", "106", "107", "108 ×3", "109 ×4",
        "110 ×4", "112 ×3", "113", "114",
        "1:1-7 + 2:1-5",
    )

    /** Words the reference quotes at the start or end of a passage, checked against Tanzil. */
    private val quoted = mapOf(
        AyahRef(2, 1) to "الم", AyahRef(2, 5) to "المفلحون",
        AyahRef(2, 257) to "خالدون", AyahRef(2, 285) to "آمن الرسول", AyahRef(3, 9) to "الميعاد",
        AyahRef(3, 18) to "شهد الله", AyahRef(3, 19) to "سريع الحساب",
        AyahRef(3, 26) to "قل اللهم", AyahRef(3, 27) to "بغير حساب",
        AyahRef(3, 190) to "إن في خلق", AyahRef(9, 128) to "لقد جاءكم رسول",
        AyahRef(18, 107) to "الفردوس نزلا", AyahRef(40, 3) to "إليه المصير",
        AyahRef(48, 29) to "محمد رسول الله",
    )

    private val counts by lazy { Assets.suras.associate { it.index to it.ayas } }

    private fun describe(f: Fadila): String {
        val ranges = f.ranges.joinToString(" + ") { r ->
            if (r.from == 1 && r.to == counts.getValue(r.sura)) {
                // Written out for al-Fātiḥa, as in the reference's closing "al-Fātiḥa + al-Baqara 1–5".
                if (f.ranges.size > 1 || r.sura == 1) "${r.sura}:1-${r.to}" else "${r.sura}"
            } else if (r.from == r.to) "${r.sura}:${r.from}" else "${r.sura}:${r.from}-${r.to}"
        }
        return if (f.times > 1) "$ranges ×${f.times}" else ranges
    }

    /**
     * Letter skeleton: no ḥarakāt or Qur'anic marks, and no alif at all (the Uthmani script writes
     * many as a small alif, e.g. خَٰلِدُونَ), one yāʾ.
     */
    private fun letters(s: String): String = s
        .filterNot { it in 'ً'..'ٟ' || it == 'ٰ' || it in 'ۖ'..'ۭ' || it == 'ـ' }
        .filterNot { it in "اٱأإآ" }
        .map { c -> if (c == 'ي') 'ى' else c }
        .joinToString("")

    @Test
    fun datasetIsValid() {
        val errors = FadailValidator.validate(Assets.fadail, Assets.suras)
        assertTrue(errors.joinToString("\n"), errors.isEmpty())
    }

    @Test
    fun passagesAreThoseOfTheReferenceAppInItsOrder() {
        assertEquals(reference, Assets.fadail.fadail.map(::describe))
    }

    @Test
    fun quotedWordsAreWhereTheReferenceSays() {
        for ((ref, words) in quoted) {
            val text = Assets.quran.text(ref) ?: error("No text for $ref")
            assertTrue("$ref should contain $words", letters(words) in letters(text))
        }
    }

    @Test
    fun everyNarrationHasASourceAndHiddenOnesStayHidden() {
        for (f in Assets.fadail.fadail) for (v in f.virtues) {
            assertTrue(f.id, v.sources.isNotEmpty())
            assertEquals(f.id, v.verified && v.grading.grade.acceptable, v.visible(showWeak = false))
            if (!v.verified) assertTrue(f.id, !v.visible(showWeak = true))
        }
    }

    @Test
    fun weakNarrationsOnlyWithTheSetting() {
        val yasin = Assets.fadail.fadail.single { it.id == "yasin" }
        assertTrue(yasin.virtues(showWeak = false).isEmpty())
        assertEquals(2, yasin.hiddenWeak(showWeak = false))
        assertTrue(yasin.virtues(showWeak = true).isNotEmpty())
        assertTrue(Assets.fadail.fadail.flatMap { it.virtues(showWeak = false) }.all { it.grading.grade.acceptable })
        // Al-Wāqiʿa at night rests on a weak narration: that occasion only shows with the setting.
        val waqia = Assets.fadail.fadail.single { it.id == "waqia" }
        assertTrue(Occasion.NIGHT !in waqia.occasions(showWeak = false))
        assertTrue(Occasion.NIGHT in waqia.occasions(showWeak = true))
    }
}
