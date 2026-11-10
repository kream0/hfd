package app.hfd.core.quran

import app.hfd.core.Assets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.Normalizer

class SuraNamesTest {
    /**
     * What both transliterations of a name share: its consonants, without the article, the
     * diacritics, the vowels (Faatiha / Fātiḥa, Lail / Layl) and h (Dhuhaa / Ḍuḥā).
     */
    private fun skeleton(name: String): String =
        Normalizer.normalize(name.replace(Regex("^(Al|An|Ar|As|Ash|At|Az|Ad|Adh|Aṭ|Aṣ|Aḍ)-"), ""), Normalizer.Form.NFD)
            .lowercase().filter { it in 'a'..'z' && it !in "aeiouyh" }.replace(Regex("(.)\\1+"), "$1")

    @Test
    fun everySuraHasItsName() {
        val names = (1..114).map { SuraNames.of(it) }
        names.forEach(::assertNotNull)
        assertEquals(114, names.toSet().size)
        assertNull(SuraNames.of(0))
        assertNull(SuraNames.of(115))
    }

    @Test
    fun theNamesAreTanzilsSurasInOrder() {
        // Against Tanzil's plain-letter name of the same sūra, so that none sits at another's place.
        for (s in Assets.suras) {
            val ours = skeleton(SuraNames.of(s.index)!!)
            val tanzil = skeleton(s.tname)
            // (Aal-i-Imraan: Tanzil's hyphen makes its first part look like an article.)
            assertTrue("${s.index}: ${SuraNames.of(s.index)} ($ours) / ${s.tname} ($tanzil)", ours == tanzil || ours.endsWith(tanzil))
        }
        assertEquals(114, SuraNames.apply(Assets.suras).map { it.tname }.toSet().size)
    }
}
