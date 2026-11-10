package app.hfd.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.sp
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
import androidx.compose.ui.platform.LocalContext
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
import app.hfd.ui.components.Loader
import app.hfd.ui.components.LocalSheets
import app.hfd.ui.components.SheetAction
import app.hfd.ui.components.SheetSpec
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

/** Reading a sūra, or reciting it from memory: the two ways into its text. */
@Composable
fun ModeSwitch(reciting: Boolean, onRead: () -> Unit, onRecite: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(CircleShape)
            .border(1.dp, P.outline, CircleShape)
            .padding(3.dp),
    ) {
        listOf(false to R.string.mode_read, true to R.string.mode_recite).forEach { (recite, label) ->
            val selected = recite == reciting
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(if (selected) P.accent.copy(alpha = if (P.isDark) 0.2f else 0.16f) else Color.Transparent)
                    .then(if (selected) Modifier.border(1.dp, P.accent.copy(alpha = 0.7f), CircleShape) else Modifier)
                    .selectable(selected, role = Role.RadioButton) { if (!selected) (if (recite) onRecite() else onRead()) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(label), style = Type.button.copy(fontSize = 13.sp), color = if (selected) P.text else P.textDim, maxLines = 1)
            }
        }
    }
}

/**
 * A sūra of the Qur'an section, in its reading mode: its text, with the juzʾ and ḥizb that begin
 * in it marked, and listening to it from the panel. A tap on an āya doesn't play it: it offers to
 * keep it as a favourite, to listen or to recite from there. The āya being recited is kept in
 * view; when the sūra ends the next one follows, and this screen with it. Opened at an āya
 * ([target]: a search result, a juzʾ, a favourite), it starts there. The other mode recites it
 * from memory ([onRecite]).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SuraScreen(sura: Int, target: AyahRef?, onTargetShown: () -> Unit, onOpen: (Int) -> Unit, onRecite: (from: Int) -> Unit, onBack: () -> Unit) {
    KeepScreenOn()
    val content by Graph.content.content.collectAsStateWithLifecycle()
    val settings by Graph.settings.state.collectAsStateWithLifecycle()
    val translations by Graph.content.translations.collectAsStateWithLifecycle()
    val np by Graph.nowPlaying.collectAsStateWithLifecycle()
    val favorites by Graph.favorites.list.collectAsStateWithLifecycle()
    val marked = remember(favorites, sura) { favorites.filter { it.sura == sura }.map { it.aya }.toSet() }
    val sheets = LocalSheets.current
    val context = LocalContext.current
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

    // Opened at an āya: the text starts there, and the āya is lit until something plays.
    var shown by remember(sura) { mutableStateOf<AyahRef?>(null) }
    LaunchedEffect(target, c, f) {
        if (target == null || c == null || f == null) return@LaunchedEffect
        val index = readingIndex(c, f, target, leading = 1) ?: return@LaunchedEffect
        autoScrolling = System.currentTimeMillis()
        listState.scrollToItem(index)
        autoScrolling = System.currentTimeMillis()
        shown = target
        onTargetShown()
    }

    // The āya at the top of the screen (the first one wholly under the title), for reciting from there.
    fun topAya(): Int? = listState.layoutInfo.let { info ->
        info.visibleItemsInfo.firstOrNull { it.offset >= info.viewportStartOffset && (it.key as? String)?.startsWith("a-") == true }
            ?.let { AyahRef.parse((it.key as String).removePrefix("a-"))?.aya }
    }
    fun recite() {
        val top = topAya()
        if (top == null || top <= 1) onRecite(1)
        else sheets(
            SheetSpec(
                title = context.getString(R.string.mode_recite),
                actions = listOf(
                    SheetAction(context.getString(R.string.recite_from_aya, top), Ic.Mic) { onRecite(top) },
                    SheetAction(context.getString(R.string.recite_from_start)) { onRecite(1) },
                ),
            ),
        )
    }

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
                        style = Type.name,
                        color = P.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                    )
                    ModeSwitch(reciting = false, onRead = {}, onRecite = ::recite, modifier = Modifier.padding(end = 8.dp))
                }
            }
            if (c == null || meta == null || f == null) {
                Box(Modifier.fillMaxSize()) { Loader(Modifier.align(Alignment.Center)) }
                return@Column
            }
            val bottomSpace = with(LocalDensity.current) { panelPx.toDp() } + 24.dp
            LazyColumn(Modifier.fillMaxSize().testTag("reading"), state = listState) {
                item(key = "intro") {
                    Text(
                        stringResource(if (meta.type == "Medinan") R.string.sura_medinan else R.string.sura_meccan) + " · " +
                            pluralStringResource(R.plurals.ayat_count, meta.ayas, meta.ayas),
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
                    current = current ?: shown,
                    playing = playing,
                    marks = { ref ->
                        if (ref.aya in marked) {
                            { Icon(Ic.Bookmark, contentDescription = stringResource(R.string.favorite_marked), tint = P.accent, modifier = Modifier.size(16.dp)) }
                        } else null
                    },
                    heading = { ref ->
                        // A juzʾ or a ḥizb begins here (a juzʾ is two aḥzāb: its first opens with it).
                        c.parts.hizbAt(ref)?.let { h -> stringResource(R.string.quran_part_mark, c.parts.juzOf(ref), h) }
                    },
                    // Reading: a tap plays nothing. It offers what can be done with the āya.
                    onTap = { ref ->
                        val isMarked = ref.aya in marked
                        sheets(
                            SheetSpec(
                                title = context.getString(R.string.quran_at, meta.tname, ref.aya),
                                actions = listOf(
                                    SheetAction(context.getString(if (isMarked) R.string.favorite_remove else R.string.favorite_add), if (isMarked) Ic.Bookmark else Ic.BookmarkBorder) {
                                        Graph.toast(if (Graph.favorites.toggle(ref)) R.string.favorite_added else R.string.favorite_removed)
                                    },
                                    SheetAction(context.getString(R.string.aya_listen_from), Ic.Play) {
                                        shown = null
                                        Graph.playSura(sura, ref)
                                    },
                                    SheetAction(context.getString(R.string.aya_recite_from), Ic.Mic) { onRecite(ref.aya) },
                                ),
                            ),
                        )
                    },
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
