package app.hfd.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hfd.Graph
import app.hfd.R
import app.hfd.core.fadail.AyahRange
import app.hfd.core.fadail.Fadila
import app.hfd.core.fadail.Localized
import app.hfd.core.playback.QuranListening
import app.hfd.core.quran.AyahRef
import app.hfd.core.quran.SuraMeta
import app.hfd.data.Content
import app.hfd.ui.components.Ic
import app.hfd.ui.components.IconBtn
import app.hfd.ui.components.KeepScreenOn
import app.hfd.ui.components.Loader
import app.hfd.ui.components.LocalSheets
import app.hfd.ui.components.PillButton
import app.hfd.ui.components.PlayingChip
import app.hfd.ui.components.SheetAction
import app.hfd.ui.components.SheetSpec
import app.hfd.ui.components.ayahTajweedSheet
import app.hfd.ui.components.readingIndex
import app.hfd.ui.components.readingItems
import app.hfd.ui.components.rememberFollow
import app.hfd.ui.components.rememberPlayingWord
import app.hfd.ui.components.tajweedSheet
import app.hfd.ui.theme.P
import app.hfd.ui.theme.Type
import app.hfd.ui.uiLanguage

/** The sūra as the reading view shows a passage: one range, the whole of it. */
private fun asPassage(s: SuraMeta): Fadila =
    Fadila(QuranListening.id(s.index), Localized(ar = s.name, fr = s.tname, en = s.tname), listOf(AyahRange(s.index, 1, s.ayas)))

/**
 * A sūra of the Qur'an section, to read. Over the text, always there, a bar says where one is
 * (the sūra, the āya at the top, its juzʾ and ḥizb) and, touched, opens the way anywhere else
 * ([QuranBrowser]: the search and the lists, [onGo]). The text opens where it was asked to
 * ([target]: a search result, a juzʾ, a favourite), else at the āya being recited, else where it
 * was last read ([app.hfd.data.ReadingPlace]): the app comes back as it was left. It follows the
 * recitation while the āya recited is in view and never pulls the reader back from elsewhere
 * ([app.hfd.ui.components.Follow]). Listening and reciting are the bar's, under every screen
 * ([app.hfd.ui.components.PlayerDock]); [onReciteAction] gives it how this screen starts a
 * recitation (from the āya at its top). A tap on an āya doesn't play it: it offers to keep it,
 * to listen or to recite from there. When the sūra ends the next one follows, and this screen
 * with it ([onOpen]).
 */
@Composable
fun SuraScreen(
    sura: Int,
    target: AyahRef?,
    onTargetShown: () -> Unit,
    onOpen: (Int) -> Unit,
    onGo: (sura: Int, aya: Int?) -> Unit,
    onRecite: (from: Int) -> Unit,
    onReciteAction: ((() -> Unit)?) -> Unit,
    onBack: () -> Unit,
) {
    KeepScreenOn()
    val content by Graph.content.content.collectAsStateWithLifecycle()
    BackHandler(onBack = onBack)
    val c = content
    val meta = c?.suras?.getOrNull(sura - 1)
    if (c == null || meta == null) {
        Box(Modifier.fillMaxSize().background(P.background)) { Loader(Modifier.align(Alignment.Center)) }
        return
    }
    SuraReader(c, meta, target, onTargetShown, onOpen, onGo, onRecite, onReciteAction, onBack)
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SuraReader(
    c: Content,
    meta: SuraMeta,
    target: AyahRef?,
    onTargetShown: () -> Unit,
    onOpen: (Int) -> Unit,
    onGo: (sura: Int, aya: Int?) -> Unit,
    onRecite: (from: Int) -> Unit,
    onReciteAction: ((() -> Unit)?) -> Unit,
    onBack: () -> Unit,
) {
    val sura = meta.index
    val settings by Graph.settings.state.collectAsStateWithLifecycle()
    val translations by Graph.content.translations.collectAsStateWithLifecycle()
    val np by Graph.nowPlaying.collectAsStateWithLifecycle()
    val player by Graph.player.state.collectAsStateWithLifecycle()
    val favorites by Graph.favorites.list.collectAsStateWithLifecycle()
    val marked = remember(favorites, sura) { favorites.filter { it.sura == sura }.map { it.aya }.toSet() }
    val sheets = LocalSheets.current
    val context = LocalContext.current
    val lang = settings.translationFor(uiLanguage)
    LaunchedEffect(settings.showTranslation, lang) {
        if (settings.showTranslation) Graph.content.ensureTranslation(lang)
    }
    val f = remember(meta) { asPassage(meta) }
    val playingSura = np?.session?.sura
    val current: AyahRef? = np?.takeIf { playingSura == sura }?.item?.ref
    val playing = rememberPlayingWord()

    // Where the text opens: the place asked for; else the āya being recited, if the text was following
    // it when it was left; else where it was last read.
    val opensAt = remember {
        val read = Graph.reading.of(sura)
        target ?: current?.takeIf { read == null || Graph.reading.following } ?: read
    }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = remember { opensAt?.let { readingIndex(c, f, it, leading = 0) } ?: 0 },
    )
    // A place asked for is lit until something plays.
    var shown by remember { mutableStateOf(target) }
    LaunchedEffect(target) {
        val t = target ?: return@LaunchedEffect
        readingIndex(c, f, t, leading = 0)?.let { listState.scrollToItem(it) }
        shown = t
        onTargetShown()
    }

    val follow = rememberFollow(listState, current?.let { readingIndex(c, f, it, leading = 0) })

    // The recitation went on to the next sūra while this one was followed: the screen goes with it.
    // (Reading elsewhere in the sūra, one stays there.)
    var wasPlayingHere by remember(sura) { mutableStateOf(false) }
    LaunchedEffect(playingSura, sura) {
        if (playingSura == sura) wasPlayingHere = true
        else if (playingSura != null && wasPlayingHere && follow.on) onOpen(playingSura)
    }

    // The āya at the top of the text: where one is. Kept as the reading's place (once the text is laid out),
    // with whether the recitation is followed there.
    val laidTop by remember { derivedStateOf { topAya(listState) } }
    val top = laidTop ?: opensAt?.aya ?: 1
    LaunchedEffect(laidTop) { laidTop?.let { Graph.reading.set(AyahRef(sura, it)) } }
    // (Read while nothing of it is recited, or elsewhere than the āya recited: the place is the reader's alone.)
    LaunchedEffect(follow.on, current != null) { Graph.reading.following = current != null && follow.on }

    fun recite() {
        if (top <= 1) onRecite(1)
        else sheets(
            SheetSpec(
                title = context.getString(R.string.recite),
                actions = listOf(
                    SheetAction(context.getString(R.string.recite_from_aya, top), Ic.Mic) { onRecite(top) },
                    SheetAction(context.getString(R.string.recite_from_start)) { onRecite(1) },
                ),
            ),
        )
    }
    val reciteNow by rememberUpdatedState(::recite)
    DisposableEffect(sura) {
        val action = { reciteNow() }
        onReciteAction(action)
        onDispose { onReciteAction(null) }
    }

    var going by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(P.background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBtn(Ic.Back, onBack, contentDescription = stringResource(R.string.detail_back))
                PlaceBar(c, meta, top, Modifier.weight(1f).padding(horizontal = 4.dp)) { going = true }
                IconBtn(Ic.Palette, { sheets(tajweedSheet(context)) }, tint = P.textDim, contentDescription = stringResource(R.string.tajweed_open))
            }
            Box(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize().testTag("reading"), state = listState) {
                    readingItems(
                        content = c,
                        fadila = f,
                        arabicSize = settings.arabicSize,
                        translation = if (settings.showTranslation) translations[lang] else null,
                        current = current ?: shown,
                        playing = playing,
                        caption = {
                            stringResource(if (meta.type == "Medinan") R.string.sura_medinan else R.string.sura_meccan) + " · " +
                                pluralStringResource(R.plurals.ayat_count, meta.ayas, meta.ayas)
                        },
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
                                        SheetAction(context.getString(R.string.tajweed_of_aya), Ic.Palette, next = {
                                            ayahTajweedSheet(context, context.getString(R.string.quran_at, meta.tname, ref.aya), c.text(ref))
                                        }),
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
                            QuranListening.before(sura)?.let { n -> PillButton("← " + c.sura(n).tname, { onGo(n, 1) }) }
                            QuranListening.after(sura)?.let { n -> PillButton(c.sura(n).tname + " →", { onGo(n, 1) }) }
                        }
                    }
                    item(key = "end") { Spacer(Modifier.height(12.dp)) }
                }
                // The text was taken elsewhere while the sūra is recited: the way back to the āya recited.
                PlayingChip(follow, player.playWhenReady, current?.let { stringResource(R.string.reader_playing_at, it.aya) }.orEmpty(), Modifier.align(Alignment.BottomCenter))
            }
        }
        // Going elsewhere, from here: the search and the lists over the text.
        AnimatedVisibility(going, enter = fadeIn() + slideInVertically { -it / 10 }, exit = fadeOut() + slideOutVertically { -it / 10 }) {
            Box(Modifier.fillMaxSize().background(P.background)) {
                BackHandler { going = false }
                QuranBrowser(
                    onOpen = { s, a ->
                        going = false
                        // (This sūra chosen in the list: its beginning.)
                        onGo(s, a ?: if (s == sura) 1 else null)
                    },
                    onClose = { going = false },
                )
            }
        }
    }
}

/** The āya at the top of the text: the first one that begins under the bar, else the one that fills the screen. */
private fun topAya(state: LazyListState): Int? {
    val info = state.layoutInfo
    val ayat = info.visibleItemsInfo.filter { (it.key as? String)?.startsWith("a-") == true }
    val first = ayat.firstOrNull { it.offset >= info.viewportStartOffset } ?: ayat.lastOrNull() ?: return null
    // A long āya that began above and still fills most of the screen is where one is.
    val before = ayat.firstOrNull()
    val at = if (before != null && before !== first && first.offset > (info.viewportStartOffset + info.viewportEndOffset) / 2) before else first
    return AyahRef.parse((at.key as String).removePrefix("a-"))?.aya
}

/**
 * Where one is, as a field one can touch: the sūra and the āya at the top of the text, under them
 * the juzʾ and the ḥizb. Touched, it opens the search and the lists.
 */
@Composable
private fun PlaceBar(c: Content, meta: SuraMeta, aya: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val ref = AyahRef(meta.index, aya.coerceIn(1, meta.ayas))
    val label = stringResource(R.string.reader_go)
    Row(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(P.surface)
            .border(1.dp, P.outline, RoundedCornerShape(18.dp))
            .clickable(onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Ic.Search, contentDescription = null, tint = P.textDim, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.reader_place, meta.tname, ref.aya), style = Type.name.copy(fontSize = 16.sp), color = P.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(R.string.reader_parts, c.parts.juzOf(ref), c.parts.hizbOf(ref), ref.aya, meta.ayas),
                style = Type.label.copy(fontSize = 12.sp), color = P.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
