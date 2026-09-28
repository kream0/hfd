package app.hfd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hfd.R
import app.hfd.core.fadail.Fadila
import app.hfd.ui.text
import app.hfd.ui.theme.P
import app.hfd.ui.theme.Type

/** Small outlined label; red for weak / fabricated narrations. */
@Composable
fun Chip(text: String, modifier: Modifier = Modifier, warn: Boolean = false, filled: Boolean = false) {
    val color = if (warn) P.accent else P.textDim
    Box(
        modifier
            .clip(CircleShape)
            .then(if (filled) Modifier.background(color) else Modifier.border(1.dp, if (warn) P.accent else P.outline, CircleShape))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text.uppercase(), style = Type.label.copy(fontSize = 10.sp), color = if (filled) Color.White else color, maxLines = 1)
    }
}

/** "2:255 · 1 āya" style summary of where a faḍīla's text is. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun rangesLabel(f: Fadila): String {
    // Whole sūras read better as their numbers ("32 · 67", "112–114") than as āya ranges.
    val refs = if ("sura" in f.tags) suraList(f.ranges.map { it.sura })
    else f.ranges.joinToString(" · ") { it.toString() }
    return refs + " · " + pluralStringResource(R.plurals.ayat_count, f.size, f.size)
}

/** "32 · 67", "113 · 114", and runs of three or more as "112–114". */
private fun suraList(suras: List<Int>): String {
    val runs = mutableListOf<IntRange>()
    for (s in suras) {
        val last = runs.lastOrNull()
        if (last != null && s == last.last + 1) runs[runs.lastIndex] = last.first..s else runs += s..s
    }
    return runs.flatMap { r -> if (r.count() >= 3) listOf("${r.first}–${r.last}") else r.map { it.toString() } }
        .joinToString(" · ")
}

@Composable
fun FadilaRow(f: Fadila, progress: Float, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DotRing(progress, Modifier.size(26.dp), color = P.saveColor(progress))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(f.title.text, style = Type.title, color = P.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(rangesLabel(f).uppercase(), style = Type.label, color = P.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                if (f.isLong) {
                    Spacer(Modifier.width(6.dp))
                    Chip(stringResource(R.string.label_long))
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            f.title.ar.orEmpty(),
            style = Type.arabicTitle.copy(fontSize = 20.sp, lineHeight = 32.sp),
            color = P.textDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 140.dp),
        )
    }
}
