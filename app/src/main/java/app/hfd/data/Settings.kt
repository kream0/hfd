package app.hfd.data

import android.content.Context
import app.hfd.core.playback.GapMode
import app.hfd.core.playback.PlanSpec
import app.hfd.core.playback.Reciter
import app.hfd.core.playback.Reciters
import app.hfd.core.progress.LearnOrder
import app.hfd.core.reminders.ReminderConfig
import kotlinx.serialization.Serializable
import java.time.LocalTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which translation of meanings shows under each āya. AUTO follows the app language. */
@Serializable
enum class TranslationChoice { AUTO, FR, EN }

@Serializable
data class AppSettings(
    val autoUpdate: Boolean = true,
    /** Translation of meanings under each āya. */
    val showTranslation: Boolean = true,
    val translation: TranslationChoice = TranslationChoice.AUTO,
    /** Qur'an text size, in sp. */
    val arabicSize: Int = 28,
    /** everyayah.com recitation (see [Reciters]). */
    val reciter: String = Reciters.DEFAULT.id,
    /** Times each āya is recited in a row; [PlanSpec.INFINITE] = until skipped. Once by default: the passage plays through. */
    val repeatEach: Int = 1,
    /** Times the whole range is played; [PlanSpec.INFINITE] = loop. */
    val repeatRange: Int = 1,
    val gap: GapMode = GapMode.NONE,
    val speed: Float = 1f,
    /** Basmala before āya 1 of a sūra (other than al-Fātiḥa and at-Tawba). */
    val basmala: Boolean = true,
    /** Minutes of practice a day (listening + reciting from memory) that count for the streak. */
    val dailyGoalMin: Int = 15,
    /** Recitations heard in the Learn steps "listen" and "repeat". */
    val learnRepeats: Int = 3,
    /** The order Home offers the passages to learn in. */
    val learnOrder: LearnOrder = LearnOrder.SHORTEST,
    /** Send diagnostics to the developer ([app.hfd.diag.Diag]). */
    val sendDiagnostics: Boolean = true,
    /** Also a few Recite recordings, to debug recognition (off; the owner switches it on). */
    val sendRecordings: Boolean = false,
    /** Recite shows the text (words light up as they are recited); off, it stays hidden until recited. */
    val reciteShowText: Boolean = true,
    /** The microphone (Android audio source) Recite last found clear; -1 until one is. */
    val reciteMic: Int = -1,
    /** The review reminder; its time in minutes after midnight. */
    val remindReviews: Boolean = false,
    val reviewsAt: Int = 19 * 60,
) {
    val reminders: ReminderConfig
        get() = ReminderConfig(reviews = remindReviews, reviewsAt = LocalTime.of(reviewsAt / 60, reviewsAt % 60))

    val reciterInfo: Reciter get() = Reciters.byId(reciter)

    fun translationFor(uiLanguage: String): TranslationLang = when (translation) {
        TranslationChoice.FR -> TranslationLang.FR
        TranslationChoice.EN -> TranslationLang.EN
        TranslationChoice.AUTO -> if (uiLanguage == "fr") TranslationLang.FR else TranslationLang.EN
    }

    companion object {
        val ARABIC_SIZES = 20..48
        /** Choices offered in Settings (S, M, L, XL). */
        val ARABIC_PRESETS = listOf(24, 28, 32, 38)
        val REPEAT_EACH = listOf(1, 3, 5, 7, 10, PlanSpec.INFINITE)
        val REPEAT_RANGE = listOf(1, 2, 3, 5, 10, PlanSpec.INFINITE)
        val SPEEDS = listOf(0.75f, 0.9f, 1f, 1.1f, 1.25f)
        val GOALS = listOf(5, 10, 15, 20, 30, 45)
        val LEARN_REPEATS = listOf(2, 3, 5, 7)
    }
}

/** App settings in SharedPreferences (`hfd_settings.xml`, included in Auto Backup). */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state.asStateFlow()
    val current: AppSettings get() = _state.value

    init {
        // Store what load() migrated, so a later choice isn't mistaken for an old default.
        if (prefs.getInt("defaults", 0) < DEFAULTS) save(_state.value)
    }

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
            arabicSize = prefs.getInt("arabicSize", d.arabicSize).coerceIn(AppSettings.ARABIC_SIZES),
            reciter = Reciters.byId(prefs.getString("reciter", null)).id
                // Until 1.3.1 Alafasy was the default (saved with every other setting): move to the new one.
                .takeUnless { prefs.getInt("defaults", 0) < 2 && it == "alafasy" } ?: d.reciter,
            repeatEach = prefs.getInt("repeatEach", d.repeatEach).takeIf { it in AppSettings.REPEAT_EACH }
                // Until 1.3.0 each āya played ×3 by default (saved with every other setting): back to the new default.
                ?.takeUnless { prefs.getInt("defaults", 0) < 1 && it == 3 } ?: d.repeatEach,
            repeatRange = prefs.getInt("repeatRange", d.repeatRange).takeIf { it in AppSettings.REPEAT_RANGE } ?: d.repeatRange,
            gap = enumOr(prefs.getString("gap", null), d.gap),
            speed = prefs.getFloat("speed", d.speed).coerceIn(0.5f, 2f),
            basmala = prefs.getBoolean("basmala", d.basmala),
            dailyGoalMin = prefs.getInt("dailyGoalMin", d.dailyGoalMin).takeIf { it in AppSettings.GOALS } ?: d.dailyGoalMin,
            learnRepeats = prefs.getInt("learnRepeats", d.learnRepeats).takeIf { it in AppSettings.LEARN_REPEATS } ?: d.learnRepeats,
            learnOrder = enumOr(prefs.getString("learnOrder", null), d.learnOrder),
            sendDiagnostics = prefs.getBoolean("sendDiagnostics", d.sendDiagnostics),
            sendRecordings = prefs.getBoolean("sendRecordings", d.sendRecordings),
            reciteShowText = prefs.getBoolean("reciteShowText", d.reciteShowText),
            reciteMic = prefs.getInt("reciteMic", d.reciteMic),
            remindReviews = prefs.getBoolean("remindReviews", d.remindReviews),
            reviewsAt = prefs.getInt("reviewsAt", d.reviewsAt).coerceIn(0, 24 * 60 - 1),
        )
    }

    private fun save(s: AppSettings) {
        prefs.edit()
            .putBoolean("autoUpdate", s.autoUpdate)
            .putBoolean("showTranslation", s.showTranslation)
            .putString("translation", s.translation.name)
            .putInt("arabicSize", s.arabicSize)
            .putString("reciter", s.reciter)
            .putInt("repeatEach", s.repeatEach)
            .putInt("repeatRange", s.repeatRange)
            .putString("gap", s.gap.name)
            .putFloat("speed", s.speed)
            .putBoolean("basmala", s.basmala)
            .putInt("dailyGoalMin", s.dailyGoalMin)
            .putInt("learnRepeats", s.learnRepeats)
            .putString("learnOrder", s.learnOrder.name)
            .putBoolean("sendDiagnostics", s.sendDiagnostics)
            .putBoolean("sendRecordings", s.sendRecordings)
            .putBoolean("reciteShowText", s.reciteShowText)
            .putInt("reciteMic", s.reciteMic)
            .putBoolean("remindReviews", s.remindReviews)
            .putInt("reviewsAt", s.reviewsAt)
            // Settings of removed features (1.6.0: passage reminders, the location they needed).
            .remove("showWeak").remove("remindKursi").remove("remindMulk").remove("mulkAt")
            .remove("remindKahf").remove("kahfAt").remove("latitude").remove("longitude").remove("prayerMethod")
            .putInt("defaults", DEFAULTS)
            .apply()
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: fallback

    companion object {
        const val PREFS = "hfd_settings"
        /**
         * Version of the defaults the stored settings were saved under (1: an āya plays once;
         * 2: Maher al-Muʿayqilī; 3: removed features' settings, the location among them, cleared).
         */
        private const val DEFAULTS = 3
    }
}
