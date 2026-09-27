package app.hfd.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hfd.Graph
import app.hfd.R
import app.hfd.core.fadail.Fadila
import app.hfd.core.quran.AyahRef
import app.hfd.data.SubRange
import app.hfd.ui.components.Chip
import app.hfd.ui.components.DotLoader
import app.hfd.ui.components.GradeChip
import app.hfd.ui.components.Ic
import app.hfd.ui.components.IconBtn
import app.hfd.ui.components.LocalSheets
import app.hfd.ui.components.PlayerPanel
import app.hfd.ui.components.SectionLabel
import app.hfd.ui.components.SheetAction
import app.hfd.ui.components.SheetSpec
import app.hfd.ui.components.neededAudio
import app.hfd.ui.components.rangesLabel
import app.hfd.ui.components.readingIndex
import app.hfd.ui.components.readingItems
import app.hfd.ui.components.sessionFor
import app.hfd.ui.label
import app.hfd.ui.text
import app.hfd.ui.theme.P
import app.hfd.ui.theme.Type
import app.hfd.ui.uiLanguage

@Composable
fun FadilaScreen(id: String, onBack: () -> Unit) {
    val content by Graph.content.content.collectAsStateWithLifecycle()
    val settings by Graph.settings.state.collectAsStateWithLifecycle()
    val translations by Graph.content.translations.collectAsStateWithLifecycle()
    val np by Graph.nowPlaying.collectAsStateWithLifecycle()
    val sheets = LocalSheets.current
    val context = LocalContext.current
    val lang = settings.translationFor(uiLanguage)
    val listState = rememberLazyListState()
    var panelPx by remember { mutableIntStateOf(0) }
    var userScrolledAt by remember { mutableLongStateOf(0L) }
    var autoScrolling by remember { mutableLongStateOf(0L) }
    LaunchedEffect(settings.showTranslation, lang) {
        if (settings.showTranslation) Graph.content.ensureTranslation(lang)
    }
    BackHandler(onBack = onBack)

    val c = content
    val f = c?.fadila(id)

    // Opening a faḍīla downloads its āyāt for the chosen reciter (small files; then fully offline).
    LaunchedEffect(f, settings.reciter, settings.basmala) {
        if (f != null) Graph.downloads.ensure(settings.reciterInfo, neededAudio(f, settings))
    }

    val current: AyahRef? = np?.takeIf { it.session.fadilaId == id }?.item?.ref

    // Keep the playing āya in view, unless the reader has just scrolled away by hand.
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress && System.currentTimeMillis() - autoScrolling > 800) {
            userScrolledAt = System.currentTimeMillis()
        }
    }
    LaunchedEffect(current, c, f) {
        if (current == null || c == null || f == null) return@LaunchedEffect
        if (System.currentTimeMillis() - userScrolledAt < 6_000) return@LaunchedEffect
        val index = readingIndex(c, f, current, leading = 1) ?: return@LaunchedEffect
        val info = listState.layoutInfo
        val visible = info.visibleItemsInfo.firstOrNull { it.index == index }
        val bottom = info.viewportEndOffset - panelPx
        if (visible != null && visible.offset >= info.viewportStartOffset && visible.offset + visible.size <= bottom) return@LaunchedEffect
        autoScrolling = System.currentTimeMillis()
        listState.animateScrollToItem(index)
        autoScrolling = System.currentTimeMillis()
    }

    Box(Modifier.fillMaxSize().background(P.background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBtn(Ic.Back, onBack, contentDescription = stringResource(R.string.detail_back))
            }
            if (c == null || f == null) {
                Box(Modifier.fillMaxSize()) { DotLoader(Modifier.align(Alignment.Center)) }
                return@Column
            }
            val title = f.title.text
            val bottomSpace = with(LocalDensity.current) { panelPx.toDp() } + 24.dp
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                item(key = "intro") { Intro(f) }
                readingItems(
                    content = c,
                    fadila = f,
                    arabicSize = settings.arabicSize,
                    translation = if (settings.showTranslation) translations[lang] else null,
                    current = current,
                    onTap = { ref -> playFrom(f, title, ref) },
                    onLongPress = { ref -> sheets(ayahActions(context, f, title, ref)) },
                )
                item(key = "end") { Spacer(Modifier.height(bottomSpace)) }
            }
        }
        if (f != null) {
            PlayerPanel(f, Modifier.align(Alignment.BottomCenter).onSizeChanged { panelPx = it.height })
        }
    }
}

/** Tap on an āya: play from it, within the chosen range if it's in there. */
private fun playFrom(f: Fadila, title: String, ref: AyahRef) {
    val ayat = f.ayat
    val index = ayat.indexOf(ref)
    var range = Graph.ranges.get(f.id, f.size)
    if (index !in range.from..range.to) {
        Graph.ranges.set(f.id, null)
        range = SubRange(0, ayat.lastIndex)
    }
    Graph.player.play(sessionFor(f, title, range), start = ref)
}

private fun ayahActions(context: android.content.Context, f: Fadila, title: String, ref: AyahRef): SheetSpec {
    val index = f.ayat.indexOf(ref)
    val range = Graph.ranges.get(f.id, f.size)
    return SheetSpec(
        title = ref.toString(),
        subtitle = title,
        actions = listOf(
            SheetAction(context.getString(R.string.ayah_play_from)) { playFrom(f, title, ref) },
            SheetAction(context.getString(R.string.ayah_repeat), Ic.RepeatOne) {
                val single = SubRange(index, index)
                Graph.ranges.set(f.id, single)
                Graph.player.play(sessionFor(f, title, single))
            },
            SheetAction(context.getString(R.string.ayah_range_start)) {
                Graph.ranges.set(f.id, SubRange(index, maxOf(index, range.to)))
            },
            SheetAction(context.getString(R.string.ayah_range_end)) {
                Graph.ranges.set(f.id, SubRange(minOf(index, range.from), index))
            },
        ),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Intro(f: Fadila) {
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Text(
            f.title.ar.orEmpty(),
            style = Type.arabicTitle.copy(textAlign = TextAlign.Center),
            color = P.text,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Text(f.title.text, style = Type.titleLarge, color = P.text, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        Text(rangesLabel(f).uppercase(), style = Type.label, color = P.textDim, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            f.occasions.forEach { Chip(stringResource(it.label)) }
            GradeChip(f.grading.grade)
            if (f.isLong) Chip(stringResource(R.string.label_long))
        }

        if (!f.grading.grade.acceptable) {
            Spacer(Modifier.height(14.dp))
            Text(stringResource(R.string.detail_weak_warning), style = Type.body, color = P.accent)
        }

        Spacer(Modifier.height(18.dp))
        SectionLabel(stringResource(R.string.detail_virtue))
        Spacer(Modifier.height(8.dp))
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(2.dp).fillMaxHeight().background(P.outline))
            Text(f.virtue.text, style = Type.body.copy(lineHeight = Type.body.lineHeight * 1.1f), color = P.text, modifier = Modifier.padding(start = 12.dp))
        }

        Spacer(Modifier.height(18.dp))
        SectionLabel(stringResource(R.string.detail_sources))
        Spacer(Modifier.height(4.dp))
        f.sources.forEach { s ->
            Row(
                Modifier.fillMaxWidth().clickable { runCatching { uri.openUri(s.url) } }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("${s.collection} ${s.number}", style = Type.title, color = P.text)
                    Text(s.narrator.uppercase(), style = Type.label, color = P.textDim)
                }
                Text("↗", style = Type.title, color = P.textDim)
            }
        }

        Spacer(Modifier.height(12.dp))
        SectionLabel(stringResource(R.string.detail_grading))
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.detail_graded_by, stringResource(f.grading.grade.label), f.grading.by),
            style = Type.title,
            color = if (f.grading.grade.acceptable) P.text else P.accent,
        )
        f.grading.note?.let {
            Spacer(Modifier.height(4.dp))
            Text(it.text, style = Type.body, color = P.textDim)
        }
        Spacer(Modifier.height(10.dp))
    }
}
