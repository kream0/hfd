package app.hfd.core.quran

import app.hfd.core.Assets
import app.hfd.core.HfdJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Locator] on readings as the recogniser writes them (the cases are from LocateBench's first run, 12 Nov). */
class LocatorTest {
    private val search: QuranSearch by lazy {
        val parts = Parts(HfdJson.decodeFromString(PartsFile.serializer(), Assets.file("quran/parts.json").readText()))
        QuranSearch(SuraNames.apply(Assets.suras), Assets.quran, parts)
    }

    private fun locate(vararg utterances: String, near: AyahRef? = null): Locator.Found? {
        val l = Locator(search, near)
        for (u in utterances) l.heard(u, final = true)?.let { return it }
        return null
    }

    private fun at(found: Locator.Found?) = found?.let { it.ref to it.word }

    @Test
    fun aPhraseSaidOnceIsFoundAtItsFirstWord() {
        assertEquals(AyahRef(2, 255) to 0, at(locate("الله لا إله إلا هو الحي القيوم لا تأخذه سنة ولا نوم")))
        // From inside an āya: the word it begins at.
        assertEquals(AyahRef(2, 255) to 7, at(locate("لا تأخذه سنة ولا نوم له ما في السماوات")))
    }

    @Test
    fun aPhraseSaidInSeveralPlacesWaitsForWhatFollows() {
        val l = Locator(search)
        assertNull(l.heard("ومما رزقناهم ينفقون", final = true))
        assertTrue(l.candidates.size > 1)
        // What follows tells: al-Anfāl, and the place is where the recitation began.
        val found = l.heard("أولئك هم المؤمنون حقا لهم درجات عند ربهم", final = true)
        assertEquals(AyahRef(8, 3) to 3, at(found))
        assertTrue(found!!.phrase.startsWith("ومما رزقناهم"))
        assertTrue(l.candidates.isEmpty())
    }

    @Test
    fun theSuraOpenSettlesAPhraseSaidInSeveralPlaces() {
        assertEquals(AyahRef(22, 35), locate("ومما رزقناهم ينفقون", near = AyahRef(22, 30))?.ref)
        assertNull(locate("ومما رزقناهم ينفقون", near = AyahRef(18, 1)))
    }

    @Test
    fun theOpeningFormulasAreNotLookedFor() {
        assertNull(locate("أعوذ بالله من الشيطان الرجيم"))
        assertNull(locate("أعوذ بالله من الشيطان الرجيم", "بسم الله الرحمن الرحيم"))
        // The basmala is al-Fātiḥa's first āya when its second follows.
        assertEquals(AyahRef(1, 1) to 0, at(locate("بسم الله الرحمن الرحيم", "الحمد لله رب العالمين")))
        assertEquals(AyahRef(1, 1) to 0, at(locate("بسم الله الرحمن الرحيم الحمد لله رب العالمين الرحمن الرحيم")))
        // Before any other passage it is not the text's.
        assertEquals(AyahRef(112, 1) to 0, at(locate("أعوذ بالله من الشيطان الرجيم", "بسم الله الرحمن الرحيم", "قل هو الله أحد الله الصمد")))
        // Without it, the phrase six sūras end an āya with waits.
        assertNull(locate("الحمد لله رب العالمين"))
        // (Hūd 41: a basmala of the text's own.)
        assertEquals(AyahRef(11, 41), locate("وقال اركبوا فيها بسم الله مجراها ومرساها")?.ref)
    }

    @Test
    fun aSuraIsFollowedFromItsOpeningLetters() {
        // The recogniser writes the letters as a broken basmala; the āya after them is found, the letters were said.
        assertEquals(AyahRef(36, 1) to 0, at(locate("بِِيَسٓمٓ", "وَٱلْقُرْءَانِ ٱلْحَكِيمِ إِنَّكَ لَمِنَ ٱلْمُرْسَلِينَ")))
        assertEquals(AyahRef(15, 1) to 0, at(locate("بِسْسْم ٱلللَّهرلرَٓ تِلْكَ ءَايَـٰتُ ٱلْكِتَـٰبِ وَقُرْءَانٍ مُّبِينٍ")))
        // After the basmala too.
        assertEquals(AyahRef(32, 1) to 0, at(locate("بِسْمِ ٱللَّهِ ٱلرَّحْمَـٰنِ ٱلرَّحِيمِ", "بِسسٱللََّّ ٱلرَّحِمٓ", "تَنزِيلُ ٱلْكِتَـٰبِ لَا رَيْبَ فِيهِ مِن رَّبِّ ٱلْعَـٰلَمِينَ")))
        // Begun at the second āya, nothing said before it: from there.
        assertEquals(AyahRef(36, 2) to 0, at(locate("وَٱلْقُرْءَانِ ٱلْحَكِيمِ إِنَّكَ لَمِنَ ٱلْمُرْسَلِينَ")))
    }

    @Test
    fun whatIsNotTheTextsBeforeAPhraseIsSetAside() {
        // Half a word before the phrase (heard from inside an āya): the phrase alone is said in
        // many places, and waits; it was found in Yūsuf by letters of «ممة» taken from the words before.
        assertNull(locate("مَمَةً وَهُمْ لَا يَشْعُرُونَ"))
        // A sūra's first words are not found in the last āya of the sūra before.
        val found = locate("بِٱلللَّٓ وَٱلْقُرْءَانِ ٱلْمَجِيدِ بَلْ عَجِبُوٓا۟ أَن جَآءَهُم مُّنذِرٌ مِّنْهُمْ")
        assertNotNull(found)
        assertEquals(50, found!!.ref.sura)
    }

    @Test
    fun aRecitationBegunOnWordsTwoSurasShareIsFollowedFromWhereItBegan() {
        // al-Aʿrāf 109–111 and ash-Shuʿarāʾ 34–36 differ at the third āya's fourth word.
        val l = Locator(search)
        assertNull(l.heard("إِنَّ هَـٰذَا لَسَـٰحِرٌ عَلِيمٌ", final = true))
        assertNull(l.heard("يُرِيدُ أَن يُخْرِجَكُم مِّنْ أَرْضِكُمْ فَمَاذَا تَأْمُرُونَ", final = true))
        val found = l.heard("قَالُوٓا۟ أَرْجِهْ وَأَخَاهُ وَأَرْسِلْ فِى ٱلْمَدَآئِنِ حَـٰشِرِينَ", final = true)
        assertEquals(AyahRef(7, 109), found?.ref)
    }

    @Test
    fun whileItIsBeingSaidAPlaceMustBeSaidTwice() {
        val l = Locator(search)
        assertNull(l.heard("ٱللَّهُ لَآ إِلَـٰهَ إِلَّا هُوَ ٱلْحَىُّ ٱلْقَيُّومُ لَا تَأْخُذُهُۥ", final = false))
        assertEquals(AyahRef(2, 255) to 0, at(l.heard("ٱللَّهُ لَآ إِلَـٰهَ إِلَّا هُوَ ٱلْحَىُّ ٱلْقَيُّومُ لَا تَأْخُذُهُۥ سِنَةٌ", final = false)))
        // The utterance's later readings are read as the one that found it.
        assertTrue(l.phrase("ٱللَّهُ لَآ إِلَـٰهَ إِلَّا هُوَ ٱلْحَىُّ ٱلْقَيُّومُ لَا تَأْخُذُهُۥ سِنَةٌ وَلَا نَوْمٌ").endsWith("نَوْمٌ"))
    }
}
