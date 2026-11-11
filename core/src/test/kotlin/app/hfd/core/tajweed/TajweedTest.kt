package app.hfd.core.tajweed

import app.hfd.core.Assets
import app.hfd.core.quran.AyahRef
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The tajwīd read from Tanzil's text ([Tajweed]): cases every manual of the rules gives, word by
 * word (the words are Tanzil's own, never retyped: a word is named by its place in the āya), and
 * the whole Qur'an's totals, so that a change to the rules shows.
 */
class TajweedTest {
    private fun text(ref: String) = Assets.quran.text(AyahRef.parse(ref)!!)!!

    /** The rules of word [word] of āya [ref], in the order of the text; "-" after a letter merged into the next. */
    private fun rules(ref: String, word: Int): String =
        Tajweed.annotate(text(ref)).filter { it.word == word }.joinToString(" ") { it.rule.name + if (it.part == Part.DROPPED) "-" else "" }

    private fun expect(ref: String, word: Int, rules: String) = assertEquals("$ref word $word (${text(ref).split(' ')[word]})", rules, rules(ref, word))

    @Test
    fun theWholeTextIsPlaced() {
        // Nothing in the Qur'an's 6,236 āyāt that the reading doesn't know what to do with.
        val doubts = (1..114).flatMap { s -> Assets.quran.suras[s - 1].indices.flatMap { a -> Tajweed.doubts(Assets.quran.suras[s - 1][a]).map { "$s:${a + 1} $it" } } }
        assertEquals(emptyList<String>(), doubts)
    }

    @Test
    fun nunSakinaAndTanwin() {
        // Said clearly before a letter of the throat: no rule.
        expect("1:7", 2, "") // أَنْعَمْتَ
        // Hidden: يُنفِقُونَ, مِن تَحْتِهَا, and a tanwīn before a qāf.
        expect("2:3", 7, "IKHFA MADD_ARID")
        expect("2:25", 9, "IKHFA")
        expect("2:25", 17, "IKHFA") // رِّزْقًا قَالُوا۟
        // Merged with ghunna, the nūn unsaid and the next letter carrying it: هُدًى مِّن, أَن يُوصَلَ.
        expect("2:5", 2, "IDGHAM_GHUNNAH-")
        expect("2:5", 3, "IDGHAM_GHUNNAH IDGHAM_NO_GHUNNAH-") // مِّن رَّبِّهِمْ
        expect("2:27", 12, "IDGHAM_GHUNNAH-")
        expect("2:27", 13, "IDGHAM_GHUNNAH MADD_NATURAL")
        // Turned into a mīm before bāʾ: مِنۢ بَعْدِ, أَلِيمٌۢ بِمَا.
        expect("2:27", 4, "IQLAB")
        expect("2:10", 8, "MADD_NATURAL IQLAB")
        // مَنْ رَاقٍ: the breath held on the nūn, no merging.
        expect("75:27", 1, "")
        // A tanwīn before a joining alif takes a kasra: no rule (قَوْمًا ٱللَّهُ), and the Name is then light.
        expect("7:164", 6, "")
        expect("7:164", 7, "HAMZAT_WASL-")
    }

    @Test
    fun mimSakina() {
        expect("2:10", 1, "MADD_NATURAL IDGHAM_SHAFAWI-") // قُلُوبِهِم مَّرَضٌ
        expect("2:10", 2, "IDGHAM_SHAFAWI TAFKHIM IKHFA")
        // تَرْمِيهِم بِحِجَارَةٍ
        expect("105:4", 0, "TAFKHIM MADD_NATURAL IKHFA_SHAFAWI")
        // A mīm with its sukūn is said clearly.
        expect("1:7", 3, "")
    }

    @Test
    fun ghunnaAndQalqala() {
        expect("2:25", 5, "GHUNNAH") // أَنَّ
        expect("4:171", 12, "GHUNNAH") // إِنَّمَا
        expect("112:3", 1, "QALQALAH") // يَلِدْ
        expect("2:3", 6, "TAFKHIM QALQALAH MADD_NATURAL") // رَزَقْنَٰهُمْ
        // At the āya's end the last letter loses its vowel: أَحَدٌ rebounds.
        expect("112:1", 3, "QALQALAH")
        // …and a qāf with a fatḥatan before the stop's alif does not.
        expect("2:25", 17, "IKHFA")
    }

    @Test
    fun longVowels() {
        expect("1:1", 2, "HAMZAT_WASL- LAM_SHAMSIYYAH- TAFKHIM MADD_NATURAL") // ٱلرَّحْمَٰنِ
        expect("1:1", 3, "HAMZAT_WASL- LAM_SHAMSIYYAH- TAFKHIM MADD_ARID") // ٱلرَّحِيمِ, the āya stops after it
        expect("1:7", 8, "HAMZAT_WASL- LAM_SHAMSIYYAH- MADD_LAZIM MADD_ARID") // ٱلضَّآلِّينَ
        expect("2:19", 3, "HAMZAT_WASL- LAM_SHAMSIYYAH- MADD_MUTTASIL") // ٱلسَّمَآءِ
        expect("2:19", 10, "MADD_MUNFASIL") // فِىٓ ءَاذَانِهِم
        expect("2:27", 11, "MADD_MUNFASIL") // بِهِۦٓ أَن
        expect("2:27", 6, "MADD_NATURAL MADD_NATURAL MADD_NATURAL") // مِيثَٰقِهِۦ
        // يَٰٓأَهْلَ: two words written as one.
        expect("4:171", 0, "MADD_MUNFASIL")
        // A long vowel dropped before a joining alif: فِى ٱلسَّمَٰوَٰتِ, عَلَى ٱللَّهِ.
        expect("4:171", 45, "")
        expect("4:171", 8, "")
        // قُرَيْشٍ: the yāʾ after a fatḥa, before the letter the āya stops on.
        expect("106:1", 1, "TAFKHIM MADD_LIN")
        // The letters that open sūras.
        expect("19:1", 0, "MADD_LAZIM MADD_NATURAL MADD_NATURAL MADD_LAZIM MADD_LAZIM")
        expect("20:1", 0, "MADD_NATURAL MADD_NATURAL")
        expect("2:1", 0, "MADD_LAZIM MADD_LAZIM")
        // ءَآلذَّكَرَيْنِ: the article after the question's hamza.
        expect("6:143", 9, "MADD_LAZIM LAM_SHAMSIYYAH- TAFKHIM")
        // أَنَا۠: the alif is only read when the reading stops on it.
        expect("5:28", 6, "SILENT-")
        // ٱلصَّلَوٰةَ: the wāw unread under its dagger alif.
        expect("2:3", 4, "HAMZAT_WASL- LAM_SHAMSIYYAH- SILENT- MADD_NATURAL")
    }

    @Test
    fun mergedLetters() {
        expect("18:1", 8, "QALQALAH IDGHAM_MUTAMATHILAYN-") // يَجْعَل لَّهُۥ
        expect("5:28", 1, "IDGHAM_MUTAJANISAYN-") // بَسَطتَ
        expect("77:20", 1, "IDGHAM_MUTAQARIBAYN- IDGHAM_SHAFAWI-") // نَخْلُقكُّم مِّن
        // بَلْ رَانَ: the breath held, the lām said.
        expect("83:14", 1, "")
    }

    @Test
    fun heavyAndLight() {
        // The Name: heavy after a fatḥa or a ḍamma, light after a kasra.
        expect("112:1", 2, "HAMZAT_WASL- TAFKHIM") // هُوَ ٱللَّهُ
        expect("1:1", 1, "HAMZAT_WASL-") // بِسْمِ ٱللَّهِ
        expect("18:1", 1, "") // لِلَّهِ
        expect("4:171", 9, "HAMZAT_WASL- TAFKHIM") // عَلَى ٱللَّهِ
        // The rāʾ.
        expect("2:27", 16, "HAMZAT_WASL- TAFKHIM") // ٱلْأَرْضِ
        expect("4:171", 16, "TAFKHIM") // مَرْيَمَ
        expect("11:41", 1, "HAMZAT_WASL- TAFKHIM MADD_NATURAL SILENT-") // ٱرْكَبُوا۟
        expect("11:41", 5, "QALQALAH MADD_NATURAL MADD_NATURAL") // مَجْر۪ىٰهَا, leant
        expect("89:4", 2, "") // يَسْرِ
        expect("97:1", 4, "HAMZAT_WASL- QALQALAH TAFKHIM") // ٱلْقَدْرِ at the stop: heavy
        expect("54:17", 3, "LAM_SHAMSIYYAH-") // لِلذِّكْرِ: a kasra, light
        expect("6:7", 5, "TAFKHIM MADD_NATURAL IKHFA") // قِرْطَاسٍ: the ṭāʾ keeps it heavy
    }

    @Test
    fun totals() {
        val counts = HashMap<Rule, Int>()
        for (sura in Assets.quran.suras) for (aya in sura) for (s in Tajweed.annotate(aya)) if (s.part == Part.DROPPED || s.rule !in setOf(Rule.IDGHAM_GHUNNAH, Rule.IDGHAM_SHAFAWI)) counts.merge(s.rule, 1, Int::plus)
        // (A merging is counted once, on the letter merged.) Against the annotation the coloured editions
        // online share (TajweedReferenceBench): the same on every count but where that one misses a rule:
        // a ghunna on a doubled letter that also carries a tanwīn, the lām of لِلنَّاسِ, بَسَطتَ, a madd
        // before ئِ, a nūn hidden inside a word that ends on a tanwīn; or marks one wrongly (ٱلْتَقَى's lām,
        // مَنْ رَاقٍ merged, a long vowel on ٱبْنَىْ ءَادَمَ).
        val expected = mapOf(
            Rule.GHUNNAH to 4946, Rule.IKHFA to 5300, Rule.IKHFA_SHAFAWI to 496, Rule.IQLAB to 562, Rule.IDGHAM_GHUNNAH to 3933,
            Rule.IDGHAM_NO_GHUNNAH to 1035, Rule.IDGHAM_SHAFAWI to 832, Rule.IDGHAM_MUTAMATHILAYN to 147, Rule.IDGHAM_MUTAJANISAYN to 62,
            Rule.IDGHAM_MUTAQARIBAYN to 13, Rule.QALQALAH to 3834, Rule.MADD_NATURAL to 37897, Rule.MADD_MUNFASIL to 3272,
            Rule.MADD_MUTTASIL to 1956, Rule.MADD_LAZIM to 148, Rule.MADD_ARID to 4535, Rule.MADD_LIN to 8, Rule.HAMZAT_WASL to 13252,
            Rule.LAM_SHAMSIYYAH to 2728, Rule.SILENT to 4234, Rule.TAFKHIM to 11605,
        )
        assertEquals(expected, counts)
    }
}
