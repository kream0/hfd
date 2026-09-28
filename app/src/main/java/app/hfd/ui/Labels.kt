package app.hfd.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import app.hfd.R
import app.hfd.core.fadail.Localized

/** "fr" or "en": the language the UI is showing (drives which text of the dataset we pick). */
val uiLanguage: String
    @Composable
    @ReadOnlyComposable
    get() = if (LocalConfiguration.current.locales[0].language == "fr") "fr" else "en"

val Localized.text: String
    @Composable
    @ReadOnlyComposable
    get() = pick(uiLanguage)

/** Western digits → Arabic-Indic (٠١٢…), for āya-end markers. */
fun arabicDigits(n: Int): String = n.toString().map { '٠' + (it - '0') }.joinToString("")

/**
 * The āya number between ornate parentheses ﴿٤﴾, kept on the same line as the last word.
 * (The U+06DD medallion would need the font to enclose the digits, which Android's text
 * stack doesn't do: the number ends up beside an empty medallion.)
 */
fun ayahEnd(aya: Int): String = "\u00A0\uFD3F" + arabicDigits(aya) + "\uFD3E"
