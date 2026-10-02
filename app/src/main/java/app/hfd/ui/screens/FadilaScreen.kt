package app.hfd.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import app.hfd.core.progress.Event
import app.hfd.core.progress.Mode
import app.hfd.core.progress.Stats
import app.hfd.core.srs.Rating
import app.hfd.ui.AppViewModel
import app.hfd.ui.components.KeepScreenOn
import app.hfd.ui.components.DotRing
import app.hfd.ui.components.PillButton
import app.hfd.ui.components.PillStyle
import app.hfd.ui.components.StrengthRing
import app.hfd.ui.components.formatInterval
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
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
import app.hfd.ui.components.Ic
import app.hfd.ui.components.IconBtn
import app.hfd.ui.components.LocalSheets
import app.hfd.ui.components.PlayerPanel
import app.hfd.ui.components.SheetAction
import app.hfd.ui.components.SheetSpec
import app.hfd.ui.components.autoAudio
import app.hfd.ui.components.rangesLabel
import app.hfd.ui.components.readingIndex
import app.hfd.ui.components.readingItems
import app.hfd.ui.components.rememberPlayingWord
import app.hfd.ui.components.sessionFor
import app.hfd.ui.text
import app.hfd.ui.theme.P
import app.hfd.ui.theme.Type
import app.hfd.ui.uiLanguage

@Composable
fun FadilaScreen(id: String, app: AppViewModel, onBack: () -> Unit) {
    KeepScreenOn()
    val content by Graph.content.content.collectAsStateWithLifecycle()
    val settings by Graph.settings.state.collectAsStateWithLifecycle()
    val translations by Graph.content.translations.collectAsStateWithLifecycle()
    val np by Graph.nowPlaying.collectAsStateWithLifecycle()
    val progress by Graph.progress.state.collectAsStateWithLifecycle()
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
    // Long sūras only get the chosen range (or their first āyāt): the rest streams and is cached,
    // and a tap on the offline chip saves everything.
    val ranges by Graph.ranges.ranges.collectAsStateWithLifecycle()
    LaunchedEffect(f, settings.reciter, settings.basmala, ranges[id]) {
        if (f != null) Graph.downloads.ensure(settings.reciterInfo, autoAudio(f, settings))
    }

    val current: AyahRef? = np?.takeIf { it.fadilaId == id }?.item?.ref
    val playing = rememberPlayingWord()

    // Keep the playing āya in view, unless the reader has just scrolled away by hand.
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress && System.currentTimeMillis() - autoScrolling > 800) {
            userScrolledAt = System.currentTimeMillis()
        }
    }
    // Reopened on a passage that no longer exists (saved before the list changed): go back.
    LaunchedEffect(c, f) { if (c != null && f == null) onBack() }
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
            val now = System.currentTimeMillis()
            LazyColumn(Modifier.fillMaxSize().testTag("reading"), state = listState) {
                item(key = "intro") { Intro(f, app) }
                readingItems(
                    content = c,
                    fadila = f,
                    arabicSize = settings.arabicSize,
                    translation = if (settings.showTranslation) translations[lang] else null,
                    current = current,
                    playing = playing,
                    marks = { ref ->
                        val p = progress[ref.key]
                        if (!p.started) null else {
                            { StrengthRing(Graph.progress.fsrs.strength(p.card, now).toFloat(), Modifier.size(16.dp)) }
                        }
                    },
                    onTap = { ref -> playFrom(f, title, ref) },
                    onLongPress = { ref -> sheets(ayahActions(context, app, f, title, ref)) },
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

private fun ayahActions(context: android.content.Context, app: AppViewModel, f: Fadila, title: String, ref: AyahRef): SheetSpec {
    val index = f.ayat.indexOf(ref)
    val range = Graph.ranges.get(f.id, f.size)
    val p = Graph.progress.state.value[ref.key]
    val strength = if (p.started) " · " + context.getString(R.string.ayah_strength, (Graph.progress.fsrs.strength(p.card, System.currentTimeMillis()) * 100).toInt()) else ""
    return SheetSpec(
        title = ref.toString(),
        subtitle = title + strength,
        actions = listOfNotNull(
            SheetAction(context.getString(R.string.ayah_learn_from)) { app.learn(f, index) },
            SheetAction(context.getString(R.string.ayah_test)) { app.review(listOf(ref)) },
            if (p.started) null else SheetAction(context.getString(R.string.ayah_mark_known), Ic.Check) {
                Graph.progress.record(Event.Rate(System.currentTimeMillis(), ref.key, Rating.EASY, Mode.LEARN))
                Graph.toast(R.string.ayah_marked)
            },
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

@OptIn(ExperimentalLayoutApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun Intro(f: Fadila, app: AppViewModel) {
    val progress by Graph.progress.state.collectAsStateWithLifecycle()
    val now = System.currentTimeMillis()
    val p = Stats.fadila(progress, f, Graph.progress.fsrs, now)
    val lang = uiLanguage
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
        if (f.isLong) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { Chip(stringResource(R.string.label_long)) }
        }

        // Memorisation: progress, next review, and the practice flows.
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            DotRing(p.fraction, Modifier.size(34.dp), color = P.saveColor(p.fraction))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.progress_memorised, p.memorised, p.total), style = Type.title, color = P.text)
                p.nextDue?.let { d ->
                    Text(
                        stringResource(R.string.progress_next_due, if (d <= now) stringResource(R.string.due_now) else stringResource(R.string.due_in, formatInterval(d - now, lang))).uppercase(),
                        style = Type.label,
                        color = P.textDim,
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(stringResource(R.string.learn), { app.learn(f) }, style = PillStyle.Filled)
            PillButton(stringResource(R.string.recite), { app.recite(f) }, icon = Ic.Mic)
            if (p.started > 0) PillButton(stringResource(R.string.test), { app.test(f) })
        }
        Spacer(Modifier.height(10.dp))
    }
}
