package app.hfd.core

import app.hfd.core.fadail.FadailFile
import app.hfd.core.quran.QuranText
import app.hfd.core.quran.SuraFile
import java.io.File

/** The app's bundled assets, as the unit tests see them. */
object Assets {
    val dir: File = File(System.getProperty("hfd.assets") ?: "../app/src/main/assets")

    fun file(path: String): File = File(dir, path).also { require(it.exists()) { "Missing asset $it" } }

    val suras by lazy { HfdJson.decodeFromString(SuraFile.serializer(), file("quran/suras.json").readText()).suras }
    val quran by lazy { QuranText.fromTanzil(file("quran/quran-uthmani.txt").readLines()) }
    val fadail by lazy { HfdJson.decodeFromString(FadailFile.serializer(), file("fadail.json").readText()) }
}
