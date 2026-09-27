package app.hfd.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import app.hfd.R
import app.hfd.core.fadail.Grade
import app.hfd.core.fadail.Localized
import app.hfd.core.fadail.Occasion

/** "fr" or "en": the language the UI is showing (drives which text of the dataset we pick). */
val uiLanguage: String
    @Composable
    @ReadOnlyComposable
    get() = if (LocalConfiguration.current.locales[0].language == "fr") "fr" else "en"

val Localized.text: String
    @Composable
    @ReadOnlyComposable
    get() = pick(uiLanguage)

@get:StringRes
val Occasion.label: Int
    get() = when (this) {
        Occasion.MORNING -> R.string.occasion_morning
        Occasion.EVENING -> R.string.occasion_evening
        Occasion.NIGHT -> R.string.occasion_night
        Occasion.BEFORE_SLEEP -> R.string.occasion_before_sleep
        Occasion.AFTER_PRAYER -> R.string.occasion_after_prayer
        Occasion.FRIDAY -> R.string.occasion_friday
        Occasion.ANY -> R.string.occasion_any
    }

@get:StringRes
val Grade.label: Int
    get() = when (this) {
        Grade.SAHIH -> R.string.grade_sahih
        Grade.HASAN -> R.string.grade_hasan
        Grade.DAIF -> R.string.grade_daif
        Grade.MAWDU -> R.string.grade_mawdu
    }

/** Western digits → Arabic-Indic (٠١٢…), for āya-end markers. */
fun arabicDigits(n: Int): String = n.toString().map { '٠' + (it - '0') }.joinToString("")

/** The āya-end medallion (U+06DD) with its number, kept on the same line as the last word. */
fun ayahEnd(aya: Int): String = " ۝" + arabicDigits(aya)
