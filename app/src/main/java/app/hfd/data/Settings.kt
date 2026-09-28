package app.hfd.data

import android.content.Context
import app.hfd.core.playback.GapMode
import app.hfd.core.playback.PlanSpec
import app.hfd.core.playback.Reciter
import app.hfd.core.playback.Reciters
import app.hfd.core.prayer.PrayerCalculator
import app.hfd.core.prayer.PrayerMethod
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
    /** Show weak (ḍaʿīf) and fabricated (mawḍūʿ) narrations, clearly labelled. */
    val showWeak: Boolean = false,
    /** Qur'an text size, in sp. */
    val arabicSize: Int = 28,
    /** everyayah.com recitation (see [Reciters]). */
    val reciter: String = Reciters.DEFAULT.id,
    /** Times each āya is recited in a row; [PlanSpec.INFINITE] = until skipped. */
    val repeatEach: Int = 3,
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
    // Reminders. Times are minutes after midnight.
    val remindKursi: Boolean = false,
    val remindMulk: Boolean = false,
    val mulkAt: Int = 22 * 60 + 30,
    val remindKahf: Boolean = false,
    val kahfAt: Int = 10 * 60,
    val remindReviews: Boolean = false,
    val reviewsAt: Int = 19 * 60,
    /** Where prayer times are computed (only stored on the phone), rounded to ~1 km. */
    val latitude: Double? = null,
    val longitude: Double? = null,
    val prayerMethod: PrayerMethod = PrayerMethod.MWL,
) {
    val reminders: ReminderConfig
        get() = ReminderConfig(
            kursi = remindKursi,
            mulk = remindMulk,
            mulkAt = LocalTime.of(mulkAt / 60, mulkAt % 60),
            kahf = remindKahf,
            kahfAt = LocalTime.of(kahfAt / 60, kahfAt % 60),
            reviews = remindReviews,
            reviewsAt = LocalTime.of(reviewsAt / 60, reviewsAt % 60),
        )

    val prayerCalculator: PrayerCalculator?
        get() = if (latitude != null && longitude != null) PrayerCalculator(latitude, longitude, prayerMethod) else null

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
            reciter = Reciters.byId(prefs.getString("reciter", null)).id,
            repeatEach = prefs.getInt("repeatEach", d.repeatEach).takeIf { it in AppSettings.REPEAT_EACH } ?: d.repeatEach,
            repeatRange = prefs.getInt("repeatRange", d.repeatRange).takeIf { it in AppSettings.REPEAT_RANGE } ?: d.repeatRange,
            gap = enumOr(prefs.getString("gap", null), d.gap),
            speed = prefs.getFloat("speed", d.speed).coerceIn(0.5f, 2f),
            basmala = prefs.getBoolean("basmala", d.basmala),
            dailyGoalMin = prefs.getInt("dailyGoalMin", d.dailyGoalMin).takeIf { it in AppSettings.GOALS } ?: d.dailyGoalMin,
            learnRepeats = prefs.getInt("learnRepeats", d.learnRepeats).takeIf { it in AppSettings.LEARN_REPEATS } ?: d.learnRepeats,
            remindKursi = prefs.getBoolean("remindKursi", d.remindKursi),
            remindMulk = prefs.getBoolean("remindMulk", d.remindMulk),
            mulkAt = prefs.getInt("mulkAt", d.mulkAt).coerceIn(0, 24 * 60 - 1),
            remindKahf = prefs.getBoolean("remindKahf", d.remindKahf),
            kahfAt = prefs.getInt("kahfAt", d.kahfAt).coerceIn(0, 24 * 60 - 1),
            remindReviews = prefs.getBoolean("remindReviews", d.remindReviews),
            reviewsAt = prefs.getInt("reviewsAt", d.reviewsAt).coerceIn(0, 24 * 60 - 1),
            latitude = prefs.getString("latitude", null)?.toDoubleOrNull(),
            longitude = prefs.getString("longitude", null)?.toDoubleOrNull(),
            prayerMethod = enumOr(prefs.getString("prayerMethod", null), d.prayerMethod),
        )
    }

    private fun save(s: AppSettings) {
        prefs.edit()
            .putBoolean("autoUpdate", s.autoUpdate)
            .putBoolean("showTranslation", s.showTranslation)
            .putString("translation", s.translation.name)
            .putBoolean("showWeak", s.showWeak)
            .putInt("arabicSize", s.arabicSize)
            .putString("reciter", s.reciter)
            .putInt("repeatEach", s.repeatEach)
            .putInt("repeatRange", s.repeatRange)
            .putString("gap", s.gap.name)
            .putFloat("speed", s.speed)
            .putBoolean("basmala", s.basmala)
            .putInt("dailyGoalMin", s.dailyGoalMin)
            .putInt("learnRepeats", s.learnRepeats)
            .putBoolean("remindKursi", s.remindKursi)
            .putBoolean("remindMulk", s.remindMulk)
            .putInt("mulkAt", s.mulkAt)
            .putBoolean("remindKahf", s.remindKahf)
            .putInt("kahfAt", s.kahfAt)
            .putBoolean("remindReviews", s.remindReviews)
            .putInt("reviewsAt", s.reviewsAt)
            .putString("latitude", s.latitude?.toString())
            .putString("longitude", s.longitude?.toString())
            .putString("prayerMethod", s.prayerMethod.name)
            .apply()
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: fallback

    companion object {
        const val PREFS = "hfd_settings"
    }
}
