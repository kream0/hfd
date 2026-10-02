package app.hfd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.hfd.Graph
import app.hfd.R
import app.hfd.core.progress.Hiding
import app.hfd.core.quran.AyahRef
import app.hfd.core.srs.Rating
import app.hfd.core.srs.SrsCard
import app.hfd.ui.ayahEnd
import app.hfd.ui.theme.P
import app.hfd.ui.theme.Type
import app.hfd.ui.uiLanguage
import java.util.Locale

/** How much of an āya shows while reciting from memory. */
enum class Visibility { FULL, FIRST_WORD, HIDDEN }

/**
 * An āya in a recitation card: whole, only its first word, or hidden behind a calm dot field.
 * The text itself is never altered; hiding only chooses what to draw.
 */
@Composable
fun ReciteAyah(
    ref: AyahRef,
    text: String,
    visibility: Visibility,
    arabicSize: Int,
    translation: String? = null,
    modifier: Modifier = Modifier,
    word: Int? = null,
) {
    Column(modifier.fillMaxWidth()) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            when (visibility) {
                Visibility.FULL -> Text(
                    withWord(text, word, P.accent) + AnnotatedString(ayahEnd(ref.aya)),
                    style = Type.quran(arabicSize),
                    color = P.text,
                    modifier = Modifier.fillMaxWidth(),
                )
                Visibility.FIRST_WORD -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(Hiding.firstWord(text), style = Type.quran(arabicSize), color = P.text)
                    Box(Modifier.weight(1f).height((arabicSize * 1.6f).dp).padding(horizontal = 12.dp)) {
                        DotGrid(Modifier.fillMaxWidth().height((arabicSize * 1.2f).dp), color = P.outline, spacing = 12.dp, radius = 1.6.dp)
                    }
                    Text(ayahEnd(ref.aya).trim(), style = Type.quran(arabicSize), color = P.textDim)
                }
                Visibility.HIDDEN -> Box(
                    Modifier.fillMaxWidth().height((arabicSize * 3.2f).dp).clip(RoundedCornerShape(18.dp)).background(P.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    DotGrid(Modifier.fillMaxWidth().height((arabicSize * 3.2f).dp), color = P.outline, spacing = 14.dp, radius = 1.6.dp)
                    Text(ayahEnd(ref.aya).trim(), style = Type.quran(arabicSize), color = P.textDim)
                }
            }
        }
        if (visibility == Visibility.FULL && !translation.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                translation,
                style = Type.translation.copy(textDirection = TextDirection.Ltr, textAlign = TextAlign.Left),
                color = P.textDim,
            )
        }
    }
}

/** "10 min", "3 d", "2 mo": how far a rating would push the next review. */
fun formatInterval(ms: Long, lang: String): String {
    val fr = lang == "fr"
    val minutes = ms / 60_000
    val days = ms / 86_400_000
    return when {
        minutes < 1 -> "<1 min"
        minutes < 60 -> "$minutes min"
        minutes < 60 * 24 -> "${minutes / 60} h"
        days < 31 -> "$days ${if (fr) "j" else "d"}"
        days < 365 -> "${days / 30} ${if (fr) "mois" else "mo"}"
        else -> String.format(Locale.US, "%.1f %s", days / 365.0, if (fr) "an" else "y")
    }
}

/** Again / Hard / Good / Easy, each with the interval it would give ([card] as it is now). */
@Composable
fun RatingButtons(card: SrsCard, onRate: (Rating) -> Unit, modifier: Modifier = Modifier) {
    val now = System.currentTimeMillis()
    val lang = uiLanguage
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (r in Rating.entries) {
            val next = Graph.progress.fsrs.review(card, r, now, fuzzSeed = 0)
            val label = when (r) {
                Rating.AGAIN -> stringResource(R.string.rate_again)
                Rating.HARD -> stringResource(R.string.rate_hard)
                Rating.GOOD -> stringResource(R.string.rate_good)
                Rating.EASY -> stringResource(R.string.rate_easy)
            }
            val accent = r == Rating.AGAIN
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, if (accent) P.accent else P.outline, RoundedCornerShape(16.dp))
                    .background(if (r == Rating.GOOD) P.inverse else Color.Transparent)
                    .clickable { onRate(r) }
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val fg = if (r == Rating.GOOD) P.onInverse else if (accent) P.accent else P.text
                Text(label.uppercase(), style = Type.labelBold, color = fg, maxLines = 1)
                Spacer(Modifier.height(2.dp))
                Text(formatInterval(next.due - now, lang), style = Type.label, color = if (r == Rating.GOOD) P.onInverse else P.textDim, maxLines = 1)
            }
        }
    }
}

/** Small ring showing an āya's memorisation strength on the red → green scale. */
@Composable
fun StrengthRing(strength: Float, modifier: Modifier = Modifier) {
    DotRing(strength, modifier, color = P.saveColor(strength), dots = 10)
}

/** Big step dots at the top of the Learn flow. */
@Composable
fun StepDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { i ->
            Box(
                Modifier
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (i <= current) P.text else P.dotOff)
                    .padding(horizontal = if (i == current) 12.dp else 3.dp),
            )
        }
    }
}

/** Pressable outlined box used for Flawless / Mistakes. */
@Composable
fun ChoiceButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, strong: Boolean = false) {
    Box(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, if (strong) P.inverse else P.outline, RoundedCornerShape(16.dp))
            .background(if (strong) P.inverse else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text.uppercase(), style = Type.labelBold, color = if (strong) P.onInverse else P.text)
    }
}
