package app.hfd.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hfd.Graph
import app.hfd.R
import app.hfd.core.fadail.AyahRange
import app.hfd.core.fadail.Fadila
import app.hfd.core.fadail.Localized
import app.hfd.core.playback.QuranListening
import app.hfd.core.quran.AyahRef
import app.hfd.core.quran.SuraMeta
import app.hfd.ui.components.DotLoader
import app.hfd.ui.components.Ic
import app.hfd.ui.components.IconBtn
import app.hfd.ui.components.KeepScreenOn
import app.hfd.ui.components.PillButton
import app.hfd.ui.components.SuraPlayerPanel
import app.hfd.ui.components.readingIndex
import app.hfd.ui.components.readingItems
import app.hfd.ui.components.rememberPlayingWord
import app.hfd.ui.theme.P
import app.hfd.ui.theme.Type
import app.hfd.ui.uiLanguage

/** The sūra as the reading view shows a passage: one range, the whole of it. */
private fun asPassage(s: SuraMeta): Fadila =
    Fadila(QuranListening.id(s.index), Localized(ar = s.name, fr = s.tname, en = s.tname), listOf(AyahRange(s.index, 1, s.ayas)))

/**
 * A sūra of the Qur'an section: its text, and listening to it. The āya being recited is kept in
 * view; a tap on an āya plays from it; when the sūra ends the next one follows, and this screen
 * with it.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SuraScreen(sura: Int, onOpen: (Int) -> Unit, onBack: () -> Unit) {
    KeepScreenOn()
    val content by Graph.content.content.collectAsStateWithLifecycle()
    val settings by Graph.settings.state.collectAsStateWithLifecycle()
    val translations by Graph.content.translations.collectAsStateWithLifecycle()
    val np by Graph.nowPlaying.collectAsStateWithLifecycle()
    val lang = settings.translationFor(uiLanguage)
    var panelPx by remember { mutableIntStateOf(0) }
    var userScrolledAt by remember { mutableLongStateOf(0L) }
    var autoScrolling by remember { mutableLongStateOf(0L) }
    LaunchedEffect(settings.showTranslation, lang) {
        if (settings.showTranslation) Graph.content.ensureTranslation(lang)
    }
    BackHandler(onBack = onBack)

    val c = content
    val meta = c?.suras?.getOrNull(sura - 1)
    val f = remember(meta) { meta?.let(::asPassage) }
    val playingSura = np?.session?.sura
    val current: AyahRef? = np?.takeIf { playingSura == sura }?.item?.ref
    val playing = rememberPlayingWord()

    // The recitation went on to another sūra while this one was open and playing: follow it.
    var wasPlayingHere by remember(sura) { mutableStateOf(false) }
    LaunchedEffect(playingSura, sura) {
        if (playingSura == sura) wasPlayingHere = true
        else if (playingSura != null && wasPlayingHere) onOpen(playingSura)
    }

    val listState = rememberLazyListState()

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
                if (meta != null) {
                    Text(
                        stringResource(R.string.sura_title, meta.index, meta.tname),
                        style = Type.title,
                        color = P.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                    )
                }
            }
            if (c == null || meta == null || f == null) {
                Box(Modifier.fillMaxSize()) { DotLoader(Modifier.align(Alignment.Center)) }
                return@Column
            }
            val bottomSpace = with(LocalDensity.current) { panelPx.toDp() } + 24.dp
            LazyColumn(Modifier.fillMaxSize().testTag("reading"), state = listState) {
                item(key = "intro") {
                    Text(
                        (stringResource(if (meta.type == "Medinan") R.string.sura_medinan else R.string.sura_meccan) + " · " +
                            pluralStringResource(R.plurals.ayat_count, meta.ayas, meta.ayas)).uppercase(),
                        style = Type.label,
                        color = P.textDim,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }
                readingItems(
                    content = c,
                    fadila = f,
                    arabicSize = settings.arabicSize,
                    translation = if (settings.showTranslation) translations[lang] else null,
                    current = current,
                    playing = playing,
                    onTap = { ref -> Graph.playSura(sura, ref) },
                )
                item(key = "around") {
                    // The sūras before and after, to read on without going back to the list.
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    ) {
                        QuranListening.before(sura)?.let { n -> PillButton("← " + c.sura(n).tname, { onOpen(n) }) }
                        QuranListening.after(sura)?.let { n -> PillButton(c.sura(n).tname + " →", { onOpen(n) }) }
                    }
                }
                item(key = "end") { Spacer(Modifier.height(bottomSpace)) }
            }
        }
        if (f != null) {
            SuraPlayerPanel(sura, f.ayat, Modifier.align(Alignment.BottomCenter).onSizeChanged { panelPx = it.height })
        }
    }
}
