package app.hfd.core.quran

import app.hfd.core.Assets
import app.hfd.core.HfdJson
import app.hfd.core.recite.Arabic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuranSearchTest {
    private val suras = SuraNames.apply(Assets.suras)
    private val parts = Parts(HfdJson.decodeFromString(PartsFile.serializer(), Assets.file("quran/parts.json").readText()))
    private val search = QuranSearch(suras, Assets.quran, parts)
    private val french = QuranText.fromTanzil(Assets.file("quran/fr.hamidullah.txt").readLines(), splitBasmala = false)

    private fun ref(key: String) = AyahRef.parse(key)!!

    @Test
    fun aReferenceIsItsAya() {
        for (q in listOf("2:255", "2 255", "2.255", "2 : 255", "٢:٢٥٥", "2-255")) assertEquals(q, listOf(ref("2:255")), search.search(q).references)
        // Not an āya of the sūra: nothing.
        assertEquals(emptyList<AyahRef>(), search.search("2:300").references)
        assertEquals(emptyList<AyahRef>(), search.search("115:1").references)
        // A sūra by name, and the āya.
        assertEquals(listOf(ref("2:255")), search.search("baqara 255").references)
        assertEquals(listOf(ref("2:255")), search.search("البقرة 255").references)
        assertEquals(listOf(ref("18:10")), search.search("kahf 10").references)
    }

    @Test
    fun aSuraByNameOrNumber() {
        assertEquals(2, search.search("baqara").suras.first())
        assertEquals(2, search.search("Al-Baqara").suras.first())
        assertEquals(listOf(36), search.search("yasin").suras)
        assertEquals(listOf(36), search.search("يس").suras.take(1))
        assertEquals(18, search.search("الكهف").suras.first())
        assertEquals(18, search.search("cave").suras.first())
        // A number: that sūra first, then those that begin with it; and the juzʾ and the ḥizb.
        val twelve = search.search("12")
        assertEquals(listOf(12), twelve.suras)
        assertEquals(listOf(true to ref("11:6"), false to parts.hizb[11]), twelve.parts.map { it.juz to it.start })
        assertEquals(listOf(1, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110, 111, 112, 113, 114), search.search("1").suras)
        // "nur": the sūra of that name before those that only hold it.
        assertEquals(24, search.search("nur").suras.first())
    }

    @Test
    fun aJuzOrAHizb() {
        for (q in listOf("juz 2", "juz2", "Juzʾ 2", "جزء 2", "الجزء ٢", "djouz 2")) {
            val p = search.search(q).parts
            assertEquals(q, listOf(QuranSearch.PartHit(true, 2, ref("2:142"))), p)
        }
        for (q in listOf("hizb 3", "Ḥizb 3", "حزب 3", "الحزب ٣")) assertEquals(q, listOf(QuranSearch.PartHit(false, 3, ref("2:142"))), search.search(q).parts)
        assertEquals(emptyList<QuranSearch.PartHit>(), search.search("juz 31").parts)
        assertEquals(emptyList<QuranSearch.PartHit>(), search.search("hizb 61").parts)
    }

    @Test
    fun wordsOfTheTextHoweverSpelt() {
        // Without vowel signs, with plain alifs: āyat al-kursī's own words.
        val r = search.search("الحي القيوم")
        // (Ṭā-Hā's «لِلْحَىِّ ٱلْقَيُّومِ» holds them too: a word may be found by its end.)
        assertEquals(listOf("2:255", "3:2", "20:111"), r.text.map { it.ref.key })
        assertEquals(3, r.textCount)
        // Which words of the āya: «ٱلْحَىُّ ٱلْقَيُّومُ» are 2:255's sixth and seventh.
        val hit = r.text.first()
        assertEquals(5 to 6, hit.from to hit.to)
        assertEquals(listOf("لحى", "لقىوم"), Arabic.words(Assets.quran.text(hit.ref)!!).subList(hit.from, hit.to + 1).map { Arabic.skeleton(it) })
        // As the muṣḥaf writes it, vowel signs and all.
        assertEquals(listOf("112:1"), search.search("قُلْ هُوَ ٱللَّهُ أَحَدٌ").text.map { it.ref.key })
        // Part of a word at either end.
        assertTrue(ref("2:255") in search.search("تأخذه سن").text.map { it.ref })
        // A frequent word: the first fifty, and the count of them all.
        val many = search.search("الرحمن")
        assertEquals(50, many.text.size)
        assertTrue(many.textCount in 51..200)
        assertEquals(ref("1:1"), many.text.first().ref)
        // One letter isn't a search.
        assertEquals(0, search.search("و").textCount)
    }

    @Test
    fun wordsOfATranslation() {
        val r = search.search("ni somnolence", french)
        assertEquals(listOf("2:255"), r.translation.map { it.ref.key })
        val hit = r.translation.first()
        assertEquals("Ni somnolence", french.text(hit.ref)!!.substring(hit.from, hit.to))
        // Accents and capitals don't count; the place found is the place in the text.
        val misericorde = search.search("MISERICORDIEUX", french)
        assertTrue(misericorde.translationCount > 50)
        val first = misericorde.translation.first()
        assertEquals("Miséricordieux", french.text(first.ref)!!.substring(first.from, first.to))
        // Not without a translation, nor for two letters, nor for a reference.
        assertEquals(0, search.search("ni somnolence").translationCount)
        assertEquals(0, search.search("ni", french).translationCount)
        assertEquals(0, search.search("baqara 255", french).translationCount)
    }

    @Test
    fun aPhraseRecitedIsFound() {
        // As the CTC recogniser writes (the text's own spelling).
        val kursi = search.recited("ٱللَّهُ لَآ إِلَـٰهَ إِلَّا هُوَ ٱلْحَىُّ ٱلْقَيُّومُ")
        assertEquals(listOf("2:255", "3:2"), kursi.take(2).map { it.ref.key })
        assertEquals(0, kursi.first().word)
        // As Whisper writes (everyday spelling), from the middle of an āya, a word misheard.
        val middle = search.recited("لا تاخذه سنة ولا نوم له ما في السماوات وما في الارظ")
        assertEquals("2:255", middle.first().ref.key)
        assertEquals(7, middle.first().word)
        assertTrue(middle.first().score > 0.9f)
        // Across two āyāt.
        val two = search.recited("قل هو الله احد الله الصمد")
        assertEquals("112:1", two.first().ref.key)
        assertEquals("112:2", two.first().last.key)
        // A phrase said many times in the text: its places, in order.
        val refrain = search.recited("فبأي آلاء ربكما تكذبان", limit = 40)
        assertEquals(31, refrain.count { it.ref.sura == 55 && it.score > 0.99f })
        assertEquals("55:13", refrain.first().ref.key)
        // Caught on the way: it begins and ends inside a word (the bench's «ـٰلِكِ يَوْمِ ٱلدِّينِ إِيَّاكَ نَعْ»).
        val cut = search.recited("لك يوم الدين اياك نعبد واياك نست")
        assertEquals("1:4", cut.first().ref.key)
        assertEquals("1:5", cut.first().last.key)
        // Not the Qur'an, or too little: nothing.
        assertEquals(emptyList<QuranSearch.Recited>(), search.recited("صباح الخير كيف حالك اليوم يا صديقي"))
        assertEquals(emptyList<QuranSearch.Recited>(), search.recited("من"))
        assertEquals(emptyList<QuranSearch.Recited>(), search.recited(""))
    }

    @Test
    fun theWholeTextIsSearchedQuickly() {
        search.prepare()
        val started = System.nanoTime()
        repeat(20) {
            search.search("الذين آمنوا")
            search.recited("ان الذين امنوا وعملوا الصالحات كانت لهم جنات الفردوس نزلا")
        }
        val each = (System.nanoTime() - started) / 20 / 1_000_000
        assertTrue("$each ms a search", each < 200)
    }
}
