package app.hfd.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.sp
import app.hfd.R

/**
 * The app's colours, after an illuminated muṣḥaf: a deep green night or warm paper, cream or ink
 * for the text, gold for what is lit, and red kept for mistakes alone.
 */
@Immutable
data class Palette(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val outline: Color,
    val text: Color,
    val textDim: Color,
    val textFaint: Color,
    /** What is lit: the selected tab, a progress, the āya being recited, ornaments. */
    val accent: Color,
    /** The main action's button, and what is written on it. */
    val primary: Color,
    val onPrimary: Color,
    /** Mistakes and failures only (a word recited wrongly, a download that failed). */
    val error: Color,
    /** The unfilled part of a ring, a bar or a calendar. */
    val track: Color,
    val inverse: Color,
    val onInverse: Color,
    /** Offline-state scale: not saved → saving → saved. */
    val saveRed: Color,
    val saveOrange: Color,
    val saveYellow: Color,
    val saveGreen: Color,
    /** The word being recited while listening, and the word to say in Recite: a clear turquoise, apart from the text, the gold and the red. */
    val wordMark: Color,
) {
    /** Colour for a download that is [progress] (0..1) complete: red → orange → yellow → green. */
    fun saveColor(progress: Float): Color {
        val p = progress.coerceIn(0f, 1f)
        return when {
            p < 1f / 3 -> lerp(saveRed, saveOrange, p * 3)
            p < 2f / 3 -> lerp(saveOrange, saveYellow, (p - 1f / 3) * 3)
            else -> lerp(saveYellow, saveGreen, (p - 2f / 3) * 3)
        }
    }
}

/** Night: deep green, cream text, gold. */
val DarkPalette = Palette(
    isDark = true,
    background = Color(0xFF0D1614),
    surface = Color(0xFF14211D),
    surfaceHigh = Color(0xFF1C2D27),
    outline = Color(0xFF2B3E37),
    text = Color(0xFFF2ECDD),
    textDim = Color(0xFFA5B4AB),
    textFaint = Color(0xFF66776E),
    accent = Color(0xFFDDB869),
    primary = Color(0xFFDDB869),
    onPrimary = Color(0xFF1B1608),
    error = Color(0xFFEF6B57),
    track = Color(0xFF24352F),
    inverse = Color(0xFFF2ECDD),
    onInverse = Color(0xFF0D1614),
    saveRed = Color(0xFFEF6B57),
    saveOrange = Color(0xFFEE9A4D),
    saveYellow = Color(0xFFE3C15A),
    saveGreen = Color(0xFF55C993),
    wordMark = Color(0xFF5FE0C6),
)

/** Paper: warm cream (no pure white anywhere), green-black ink, deep green and ochre. */
val LightPalette = Palette(
    isDark = false,
    background = Color(0xFFF4EEDF),
    surface = Color(0xFFFBF7EC),
    surfaceHigh = Color(0xFFE9E1CD),
    outline = Color(0xFFD5CBB3),
    text = Color(0xFF1D2924),
    textDim = Color(0xFF5E6A61),
    textFaint = Color(0xFF9AA091),
    accent = Color(0xFFA6771F),
    primary = Color(0xFF1F5B4A),
    onPrimary = Color(0xFFFBF7EC),
    error = Color(0xFFB93A2B),
    track = Color(0xFFDDD4BE),
    inverse = Color(0xFF1D2924),
    onInverse = Color(0xFFFBF7EC),
    saveRed = Color(0xFFB93A2B),
    saveOrange = Color(0xFFC2651A),
    saveYellow = Color(0xFFA6771F),
    saveGreen = Color(0xFF237F5A),
    wordMark = Color(0xFF0E7C72),
)

val LocalPalette = staticCompositionLocalOf { DarkPalette }

/** Shorthand for the current palette. */
val P: Palette
    @Composable
    @ReadOnlyComposable
    get() = LocalPalette.current

object Fonts {
    /**
     * Source Serif 4 (OFL; tools/fonts/make.py): names and titles. It writes the transliteration's
     * letters well (Faḍāʾil, Āl ʿImrān).
     */
    val Serif = FontFamily(
        Font(R.font.serif_regular, FontWeight.Normal),
        Font(R.font.serif_semibold, FontWeight.SemiBold),
    )
    /** Source Sans 3 (OFL): everything else in Latin letters. */
    val Sans = FontFamily(
        Font(R.font.sans_regular, FontWeight.Normal),
        Font(R.font.sans_medium, FontWeight.Medium),
        Font(R.font.sans_semibold, FontWeight.SemiBold),
    )
    /** Amiri Quran (OFL): designed for Unicode Qur'an text such as Tanzil's Uthmani script. */
    val Quran = FontFamily(Font(R.font.amiri_quran, FontWeight.Normal))
}

object Type {
    /** A screen's title. */
    val display = TextStyle(fontFamily = Fonts.Serif, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 36.sp)
    val displaySmall = TextStyle(fontFamily = Fonts.Serif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp)
    /** Figures that change in place (an āya's number, minutes): of equal widths. */
    val clock = TextStyle(fontFamily = Fonts.Sans, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp, fontFeatureSettings = "tnum, lnum")
    val titleLarge = TextStyle(fontFamily = Fonts.Serif, fontWeight = FontWeight.SemiBold, fontSize = 25.sp, lineHeight = 31.sp)
    /** A passage's or a sūra's name in a list. */
    val name = TextStyle(fontFamily = Fonts.Serif, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp)
    val title = TextStyle(fontFamily = Fonts.Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 21.sp)
    val body = TextStyle(fontFamily = Fonts.Sans, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 21.sp)
    /** Secondary lines under a title, descriptions. */
    val label = TextStyle(fontFamily = Fonts.Sans, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp, fontFeatureSettings = "lnum")
    val labelBold = label.copy(fontWeight = FontWeight.SemiBold)
    /** The small capitals above a section. */
    val overline = TextStyle(fontFamily = Fonts.Sans, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 1.1.sp)
    val button = TextStyle(fontFamily = Fonts.Sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp)
    val input = TextStyle(fontFamily = Fonts.Sans, fontWeight = FontWeight.Medium, fontSize = 17.sp)

    /** Qur'an text: generous line height so ḥarakāt and waqf marks never collide. */
    fun quran(size: Int) = TextStyle(
        fontFamily = Fonts.Quran,
        fontSize = size.sp,
        lineHeight = (size * 2.05f).sp,
        textDirection = TextDirection.Rtl,
        textAlign = TextAlign.Right,
    )
    val arabicTitle = TextStyle(fontFamily = Fonts.Quran, fontSize = 26.sp, lineHeight = 44.sp, textDirection = TextDirection.Rtl)
    /** The translation of the meanings, set as a book's text. */
    val translation = TextStyle(fontFamily = Fonts.Serif, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp)
}

/** [dark]: the night theme, otherwise paper (see [app.hfd.data.ThemeMode]). */
@Composable
fun HfdTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val p = if (dark) DarkPalette else LightPalette
    val scheme = if (dark) {
        darkColorScheme(
            primary = p.primary, onPrimary = p.onPrimary,
            background = p.background, onBackground = p.text,
            surface = p.surface, onSurface = p.text,
            surfaceVariant = p.surfaceHigh, onSurfaceVariant = p.textDim,
            outline = p.outline,
        )
    } else {
        lightColorScheme(
            primary = p.primary, onPrimary = p.onPrimary,
            background = p.background, onBackground = p.text,
            surface = p.surface, onSurface = p.text,
            surfaceVariant = p.surfaceHigh, onSurfaceVariant = p.textDim,
            outline = p.outline,
        )
    }
    val typography = Typography(
        bodyLarge = Type.body,
        bodyMedium = Type.body,
        titleMedium = Type.title,
        labelSmall = Type.label,
    )
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = typography) {
            CompositionLocalProvider(LocalContentColor provides p.text, content = content)
        }
    }
}
