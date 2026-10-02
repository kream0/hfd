package app.hfd.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hfd.Graph
import app.hfd.core.playback.EveryAyah
import app.hfd.core.playback.PlanItem
import app.hfd.core.playback.WordTimings
import app.hfd.core.quran.AyahRef
import app.hfd.core.recite.Arabic
import app.hfd.playback.NowPlaying
import app.hfd.playback.PlayerProgress
import app.hfd.ui.theme.P
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The word being recited while listening, from what is playing, the player's position and the
 * reciter's word timings. [at] is read by each āya's block: only the playing one follows the
 * position, and it recomposes when the word changes, not at each tick.
 */
class PlayingWord(
    private val np: State<NowPlaying?>,
    private val timings: State<WordTimings?>,
    private val progress: State<PlayerProgress>,
) {
    private fun of(ref: AyahRef, basmala: Boolean): Int? {
        preview.value?.let { (r, w) -> return if (!basmala && r == ref) w else null }
        val now = np.value ?: return null
        val item = now.item
        val playing = if (basmala) item is PlanItem.Basmala && item.ref.sura == ref.sura else item is PlanItem.Ayah && item.ref == ref
        if (!playing) return null
        val t = timings.value?.takeIf { it.reciter == now.reciter.id } ?: return null
        // The position of this very item: at a change of āya (or repetition) the player's position
        // can still be the previous item's end for a moment, which would light a word far in.
        val p = progress.value.takeIf { it.mediaId == item?.mediaId } ?: return null
        return t.wordAt(if (basmala) EveryAyah.BASMALA else ref, p.positionMs)
    }

    /** The word of [ref] being recited, or null when it isn't playing (or has no timings). */
    @Composable
    fun at(ref: AyahRef): Int? = remember(this, ref) { derivedStateOf { of(ref, basmala = false) } }.value

    companion object {
        /** A word shown as being recited, without playing (the screenshot test). */
        val preview = mutableStateOf<Pair<AyahRef, Int>?>(null)
    }

    /** The word of [sura]'s basmala being recited (it plays as al-Fātiḥa's first āya). */
    @Composable
    fun atBasmala(sura: Int): Int? = remember(this, sura) { derivedStateOf { of(AyahRef(sura, 1), basmala = true) } }.value
}

@Composable
fun rememberPlayingWord(): PlayingWord {
    val np = Graph.nowPlaying.collectAsStateWithLifecycle()
    val progress = Graph.player.progress.collectAsStateWithLifecycle()
    val reciter = np.value?.reciter
    val timings = produceState<WordTimings?>(null, reciter) {
        value = reciter?.let { withContext(Dispatchers.IO) { Graph.words.of(it) } }
    }
    return remember(np, timings, progress) { PlayingWord(np, timings, progress) }
}

/**
 * Qur'an [text] (and [end], the āya-end marker) with its [word] (counted as [Arabic.words] does) in
 * electric blue on a pill of the same blue: the word being recited while listening (red stays for
 * mistakes). The text itself is untouched.
 */
@Composable
fun QuranText(
    text: String,
    word: Int?,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    end: String = "",
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val range = remember(text, word) { word?.let { Arabic.wordRanges(text).getOrNull(it) } }
    val mark = P.wordMark
    val shown = remember(text, end, range, mark) {
        if (range == null) AnnotatedString(text + end)
        else AnnotatedString(text + end, listOf(AnnotatedString.Range(SpanStyle(color = mark), range.first, range.second)))
    }
    Text(
        shown,
        style = style,
        color = color,
        onTextLayout = { layout = it },
        modifier = modifier.drawBehind {
            val l = layout ?: return@drawBehind
            val (from, to) = range ?: return@drawBehind
            if (to > l.layoutInput.text.length) return@drawBehind
            val box = l.getPathForRange(from, to).getBounds()
            // The line box is tall (the text's generous line height): the pill hugs the letters.
            val inset = box.height * 0.12f
            val pad = 4.dp.toPx()
            drawRoundRect(
                mark.copy(alpha = 0.16f),
                topLeft = Offset(box.left - pad, box.top + inset),
                size = Size(box.width + 2 * pad, box.height - 2 * inset),
                cornerRadius = CornerRadius(10.dp.toPx()),
            )
        },
    )
}
