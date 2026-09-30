package app.hfd.data

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList

/**
 * The app's language: French by default (the owner's), English, or the phone's. From Android 13
 * it is the system's per-app language (the whole app follows it: screens, notifications, and
 * Android's own Settings → Apps → HFD → Language, which [adopt] reads back); below, each activity
 * is given it ([wrap]).
 */
object AppLocale {
    private fun list(language: AppLanguage): LocaleList =
        if (language.tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(language.tag)

    /** Makes [language] the app's (Android 13+; it recreates the activities if needed). */
    fun apply(context: Context, language: AppLanguage) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        runCatching {
            val manager = context.getSystemService(LocaleManager::class.java) ?: return
            val want = list(language)
            if (manager.applicationLocales != want) manager.applicationLocales = want
        }
    }

    /**
     * The language chosen in Android's settings for the app since it last ran, if it differs from
     * [current] (Android 13+): kept, rather than set back.
     */
    fun adopt(context: Context, current: AppLanguage): AppLanguage? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val tag = runCatching { context.getSystemService(LocaleManager::class.java)?.applicationLocales?.get(0)?.language }.getOrNull()
            ?: return null
        val chosen = AppLanguage.entries.firstOrNull { it.tag == tag } ?: return null
        return chosen.takeIf { it != current }
    }

    /** Below Android 13: [base] with its resources in [language]. */
    fun wrap(base: Context, language: AppLanguage): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU || language.tag.isEmpty()) return base
        val config = Configuration(base.resources.configuration)
        config.setLocales(list(language))
        return base.createConfigurationContext(config)
    }
}
