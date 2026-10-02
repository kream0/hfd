package app.hfd.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.hfd.R
import app.hfd.core.fadail.Fadila
import app.hfd.core.quran.AyahRef
import app.hfd.core.quran.QuranText
import app.hfd.data.Content
import app.hfd.ui.ayahEnd
import app.hfd.ui.theme.P
import app.hfd.ui.theme.Type

/** Key of the list item showing [ref], for scrolling the reading view to it. */
fun ayahKey(ref: AyahRef): String = "a-${ref.key}"

/** Index in the list of [ref]'s block, given [leading] items before the reading items. */
fun readingIndex(content: Content, fadila: Fadila, ref: AyahRef, leading: Int): Int? {
    var i = leading
    for (range in fadila.ranges) {
        i++ // sūra header
        if (range.from == 1 && content.quran.basmalaOf(range.sura) != null) i++
        if (ref in range) return i + (ref.aya - range.from)
        i += range.size
    }
    return null
}

/**
 * The reading view of a faḍīla: per range a sūra header, the basmala when the range starts a
 * sūra (except al-Fātiḥa, where it is āya 1, and at-Tawba), then one block per āya. The text is
 * shown exactly as Tanzil writes it; [current] gets a calm highlight, nothing moves in the text.
 * With [playing], the word being recited is lit too.
 */
fun LazyListScope.readingItems(
    content: Content,
    fadila: Fadila,
    arabicSize: Int,
    translation: QuranText?,
    current: AyahRef? = null,
    playing: PlayingWord? = null,
    marks: (AyahRef) -> (@Composable () -> Unit)? = { null },
    onTap: ((AyahRef) -> Unit)? = null,
    onLongPress: ((AyahRef) -> Unit)? = null,
) {
    fadila.ranges.forEachIndexed { i, range ->
        val sura = content.sura(range.sura)
        item(key = "s-$i-${range.sura}-${range.from}") {
            SuraHeader(range.sura, sura.name, sura.tname, range.toString())
        }
        val basmala = if (range.from == 1) content.quran.basmalaOf(range.sura) else null
        if (basmala != null) {
            item(key = "b-$i-${range.sura}") { Basmala(basmala, arabicSize, playing?.atBasmala(range.sura)) }
        }
        for (ref in range.ayat()) {
            item(key = ayahKey(ref)) {
                AyahBlock(
                    ref = ref,
                    text = content.text(ref),
                    translation = translation?.text(ref),
                    arabicSize = arabicSize,
                    highlighted = ref == current,
                    word = playing?.at(ref),
                    mark = marks(ref),
                    onTap = onTap?.let { tap -> { tap(ref) } },
                    onLongPress = onLongPress?.let { press -> { press(ref) } },
                )
            }
        }
    }
}

@Composable
private fun SuraHeader(index: Int, arabic: String, translit: String, range: String) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 22.dp, bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("سورة $arabic", style = Type.arabicTitle, color = P.text, textAlign = TextAlign.Center)
        Text(stringResource(R.string.sura_header, index, translit, range).uppercase(), style = Type.label, color = P.textDim)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(5) { Box(Modifier.size(3.dp).clip(CircleShape).background(P.textFaint)) }
        }
    }
}

@Composable
private fun Basmala(text: String, arabicSize: Int, word: Int?) {
    QuranText(
        text,
        word,
        style = Type.quran(arabicSize).copy(textAlign = TextAlign.Center),
        color = P.text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AyahBlock(
    ref: AyahRef,
    text: String,
    translation: String?,
    arabicSize: Int,
    highlighted: Boolean,
    word: Int? = null,
    mark: (@Composable () -> Unit)? = null,
    onTap: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
) {
    val clickable = if (onTap != null || onLongPress != null) {
        Modifier.combinedClickable(onClick = { onTap?.invoke() }, onLongClick = onLongPress)
    } else {
        Modifier
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (highlighted) P.surfaceHigh else Color.Transparent)
            .then(clickable)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        // RTL row: the āya starts on the right; the gutter on the left holds its reference, the
        // highlight dot and optional marks (e.g. memorisation strength).
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Row(verticalAlignment = Alignment.Top) {
                QuranText(
                    text,
                    word,
                    style = Type.quran(arabicSize),
                    color = P.text,
                    modifier = Modifier.weight(1f).padding(end = 10.dp),
                    end = ayahEnd(ref.aya),
                )
                Column(Modifier.padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(if (highlighted) P.accent else Color.Transparent))
                    Spacer(Modifier.height(4.dp))
                    Text(ref.key, style = Type.label, color = if (highlighted) P.text else P.textFaint)
                    if (mark != null) {
                        Spacer(Modifier.height(6.dp))
                        mark()
                    }
                }
            }
        }
        if (!translation.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                translation,
                style = Type.translation.copy(textDirection = TextDirection.Ltr, textAlign = TextAlign.Left),
                color = P.textDim,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
