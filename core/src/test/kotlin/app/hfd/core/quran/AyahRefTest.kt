package app.hfd.core.quran

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AyahRefTest {
    @Test
    fun parsesAndPrintsKeys() {
        assertEquals(AyahRef(2, 255), AyahRef.parse("2:255"))
        assertEquals("2:255", AyahRef(2, 255).key)
        assertNull(AyahRef.parse("0:1"))
        assertNull(AyahRef.parse("115:1"))
        assertNull(AyahRef.parse("2:0"))
        assertNull(AyahRef.parse("2255"))
        assertNull(AyahRef.parse("a:b"))
    }

    @Test
    fun ordersBySuraThenAya() {
        assertTrue(AyahRef(2, 286) < AyahRef(3, 1))
        assertTrue(AyahRef(2, 9) < AyahRef(2, 10))
        assertEquals(listOf(AyahRef(1, 7), AyahRef(2, 1)), listOf(AyahRef(2, 1), AyahRef(1, 7)).sorted())
    }
}
