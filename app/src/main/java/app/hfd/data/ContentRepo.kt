package app.hfd.data

import android.content.Context
import android.util.Log
import app.hfd.core.HfdJson
import app.hfd.core.fadail.FadailFile
import app.hfd.core.fadail.Fadila
import app.hfd.core.quran.AyahRef
import app.hfd.core.quran.Parts
import app.hfd.core.quran.PartsFile
import app.hfd.core.quran.QuranSearch
import app.hfd.core.quran.QuranText
import app.hfd.core.quran.SuraFile
import app.hfd.core.quran.SuraMeta
import app.hfd.core.quran.SuraNames
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
    /** The reference list, whole and in its order. */
    val allFadail: List<Fadila>,
    /** Ids of the passages the owner took out of it (Settings: which passages show). */
    val hidden: Set<String> = emptySet(),
    /** The muṣḥaf's ajzāʾ and aḥzāb (Tanzil's metadata). */
    val parts: Parts,
    search: QuranSearch? = null,
) {
    /** Finding one's way in the whole text (built at its first use: [QuranSearch.prepare]). */
    val search: QuranSearch = search ?: QuranSearch(suras, quran, parts)

    /**
     * The passages the app shows, learns and reviews: the reference list without the hidden ones.
     * A hidden passage is nowhere (lists, learning path, reviews, playing them all, [fadila]) but
     * in the settings' list, where it can be shown again; its progress is kept.
     */
    val fadail: List<Fadila> = if (hidden.isEmpty()) allFadail else allFadail.filter { it.id !in hidden }
    private val byId = fadail.associateBy { it.id }
    fun fadila(id: String): Fadila? = byId[id]
    /**
     * The shown passages' āyāt (keys), for what counts as due or memorised; null when nothing is
     * hidden. An āya of a hidden passage that a shown one also holds (al-Kahf's last four) stays.
     */
    val shownAyat: Set<String>? = if (hidden.isEmpty()) null else fadail.flatMapTo(HashSet()) { f -> f.ayat.map { it.key } }
    fun hiding(ids: Set<String>): Content = if (ids == hidden) this else Content(suras, quran, allFadail, ids, parts, search)
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
class ContentRepo(private val context: Context, scope: CoroutineScope, private val hidden: () -> Set<String> = { emptySet() }) {
    private val _content = MutableStateFlow<Content?>(null)
    val content: StateFlow<Content?> = _content.asStateFlow()

    private val _translations = MutableStateFlow<Map<TranslationLang, QuranText>>(emptyMap())
    val translations: StateFlow<Map<TranslationLang, QuranText>> = _translations.asStateFlow()
    private val translationLock = Mutex()

    private val cacheDir = File(context.filesDir, "text")

    init {
        scope.launch {
            _content.value = withContext(Dispatchers.IO) { load() }.hiding(hidden())
        }
    }

    /** The passages taken out of the list changed ([Content.hidden]). */
    fun hide(ids: Set<String>) {
        _content.value = _content.value?.hiding(ids)
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
        // Tanzil's metadata, with the names in the transliteration the passages' titles use.
        val suras = SuraNames.apply(HfdJson.decodeFromString(SuraFile.serializer(), asset("quran/suras.json")).suras)
        val quran = cached("quran/quran-uthmani.txt", "quran-uthmani", splitBasmala = true)
        val fadail = HfdJson.decodeFromString(FadailFile.serializer(), asset("fadail.json")).fadail
        val parts = Parts(HfdJson.decodeFromString(PartsFile.serializer(), asset("quran/parts.json")))
        return Content(suras, quran, fadail, parts = parts)
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
