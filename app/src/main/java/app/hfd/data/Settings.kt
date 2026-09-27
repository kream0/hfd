package app.hfd.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which translation of meanings shows under each āya. AUTO follows the app language. */
enum class TranslationChoice { AUTO, FR, EN }

data class AppSettings(
    val autoUpdate: Boolean = true,
    /** Translation of meanings under each āya. */
    val showTranslation: Boolean = true,
    val translation: TranslationChoice = TranslationChoice.AUTO,
    /** Show weak (ḍaʿīf) and fabricated (mawḍūʿ) narrations, clearly labelled. */
    val showWeak: Boolean = false,
    /** Qur'an text size, in sp. */
    val arabicSize: Int = 28,
) {
    fun translationFor(uiLanguage: String): TranslationLang = when (translation) {
        TranslationChoice.FR -> TranslationLang.FR
        TranslationChoice.EN -> TranslationLang.EN
        TranslationChoice.AUTO -> if (uiLanguage == "fr") TranslationLang.FR else TranslationLang.EN
    }

    companion object {
        val ARABIC_SIZES = 20..48
        /** Choices offered in Settings (S, M, L, XL). */
        val ARABIC_PRESETS = listOf(24, 28, 32, 38)
    }
}

/** App settings in SharedPreferences (`hfd_settings.xml`, included in Auto Backup). */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state.asStateFlow()
    val current: AppSettings get() = _state.value

    fun update(block: (AppSettings) -> AppSettings) {
        val next = block(_state.value)
        _state.value = next
        save(next)
    }

    private fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            autoUpdate = prefs.getBoolean("autoUpdate", d.autoUpdate),
            showTranslation = prefs.getBoolean("showTranslation", d.showTranslation),
            translation = enumOr(prefs.getString("translation", null), d.translation),
            showWeak = prefs.getBoolean("showWeak", d.showWeak),
            arabicSize = prefs.getInt("arabicSize", d.arabicSize).coerceIn(AppSettings.ARABIC_SIZES),
        )
    }

    private fun save(s: AppSettings) {
        prefs.edit()
            .putBoolean("autoUpdate", s.autoUpdate)
            .putBoolean("showTranslation", s.showTranslation)
            .putString("translation", s.translation.name)
            .putBoolean("showWeak", s.showWeak)
            .putInt("arabicSize", s.arabicSize)
            .apply()
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: fallback

    companion object {
        const val PREFS = "hfd_settings"
    }
}
