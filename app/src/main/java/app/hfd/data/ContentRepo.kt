package app.hfd.data

import android.content.Context
import android.util.Log
import app.hfd.core.HfdJson
import app.hfd.core.fadail.FadailFile
import app.hfd.core.fadail.Fadila
import app.hfd.core.quran.AyahRef
import app.hfd.core.quran.QuranText
import app.hfd.core.quran.SuraFile
import app.hfd.core.quran.SuraMeta
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** Everything the app shows that ships inside the APK. */
class Content(
    val suras: List<SuraMeta>,
    val quran: QuranText,
    val fadail: List<Fadila>,
) {
    private val byId = fadail.associateBy { it.id }
    fun fadila(id: String): Fadila? = byId[id]
    fun sura(index: Int): SuraMeta = suras[index - 1]
    fun text(ref: AyahRef): String = quran.text(ref).orEmpty()
}

enum class TranslationLang(val asset: String, val code: String) {
    FR("quran/fr.hamidullah.txt", "fr"),
    EN("quran/en.sahih.txt", "en"),
}

/**
 * Loads the bundled Qur'an text (Tanzil), sūra metadata and the faḍāʾil dataset. The Tanzil file
 * is parsed once into a compact JSON cache (rebuilt whenever the bundled file changes), which is
 * what later launches read.
 */
class ContentRepo(private val context: Context, scope: CoroutineScope) {
    private val _content = MutableStateFlow<Content?>(null)
    val content: StateFlow<Content?> = _content.asStateFlow()

    private val _translations = MutableStateFlow<Map<TranslationLang, QuranText>>(emptyMap())
    val translations: StateFlow<Map<TranslationLang, QuranText>> = _translations.asStateFlow()
    private val translationLock = Mutex()

    private val cacheDir = File(context.filesDir, "text")

    init {
        scope.launch {
            _content.value = withContext(Dispatchers.IO) { load() }
        }
    }

    /** Loads a translation (once) so the reading view can show it under each āya. */
    suspend fun ensureTranslation(lang: TranslationLang) {
        if (_translations.value.containsKey(lang)) return
        translationLock.withLock {
            if (_translations.value.containsKey(lang)) return
            val text = withContext(Dispatchers.IO) { cached(lang.asset, "trans-${lang.code}", splitBasmala = false) }
            _translations.value = _translations.value + (lang to text)
        }
    }

    private fun load(): Content {
        val suras = HfdJson.decodeFromString(SuraFile.serializer(), asset("quran/suras.json")).suras
        val quran = cached("quran/quran-uthmani.txt", "quran-uthmani", splitBasmala = true)
        val fadail = HfdJson.decodeFromString(FadailFile.serializer(), asset("fadail.json")).fadail
        return Content(suras, quran, fadail)
    }

    private fun asset(path: String): String = context.assets.open(path).use { it.readBytes().decodeToString() }

    /** Tanzil file → compact JSON in files/text, reused while the bundled file is unchanged. */
    private fun cached(assetPath: String, name: String, splitBasmala: Boolean): QuranText {
        val bytes = context.assets.open(assetPath).use { it.readBytes() }
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val file = File(cacheDir, "$name.json")
        if (file.exists()) {
            val cached = runCatching { HfdJson.decodeFromString(QuranText.serializer(), file.readText()) }.getOrNull()
            if (cached != null && cached.schema == QuranText.SCHEMA && cached.source == sha) return cached
        }
        val parsed = QuranText.fromTanzil(bytes.decodeToString().lines(), source = sha, splitBasmala = splitBasmala)
        runCatching {
            cacheDir.mkdirs()
            val tmp = File(cacheDir, "$name.json.tmp")
            tmp.writeText(HfdJson.encodeToString(QuranText.serializer(), parsed))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }.onFailure { Log.w("ContentRepo", "Couldn't cache $name", it) }
        return parsed
    }
}
