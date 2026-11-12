package app.hfd.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import app.hfd.ui.components.IconBtn
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hfd.Graph
import app.hfd.R
import app.hfd.core.quran.AyahRef
import app.hfd.core.quran.QuranSearch
import app.hfd.core.quran.SuraMeta
import app.hfd.core.recite.Arabic
import app.hfd.data.Content
import app.hfd.recite.ModelState
import app.hfd.recite.ReciteSession
import app.hfd.ui.components.Equalizer
import app.hfd.ui.components.Ic
import app.hfd.ui.components.Loader
import app.hfd.ui.components.LocalSheetClose
import app.hfd.ui.components.LocalSheets
import app.hfd.ui.components.PillButton
import app.hfd.ui.components.PillStyle
import app.hfd.ui.components.ScreenHeader
import app.hfd.ui.components.SectionLabel
import app.hfd.ui.components.Segmented
import app.hfd.ui.components.SheetSpec
import app.hfd.ui.components.Star
import app.hfd.ui.components.card
import app.hfd.ui.theme.P
import app.hfd.ui.theme.Type
import app.hfd.ui.uiLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** What the Qur'an section lists. */
private enum class QuranView { SURAS, JUZ, HIZB, FAVORITES }

/** The Qur'an section's tab: [QuranBrowser]. */
@Composable
fun QuranScreen(onOpen: (sura: Int, aya: Int?) -> Unit) = QuranBrowser(onOpen)

/**
 * The way into the Qur'an's text: a search of the whole of it (a reference, a name, words of the
 * text or of the translation, or a phrase recited aloud), and its lists (sūras, ajzāʾ, aḥzāb, the
 * āyāt kept). The same on the Coran tab and over a sūra being read ([onClose]: opened from the
 * bar over the text, it starts in the search field and can be closed): one place to go anywhere
 * from, wherever one is.
 */
@Composable
fun QuranBrowser(onOpen: (sura: Int, aya: Int?) -> Unit, onClose: (() -> Unit)? = null) {
    val content by Graph.content.content.collectAsStateWithLifecycle()
    val np by Graph.nowPlaying.collectAsStateWithLifecycle()
    val ui by Graph.player.state.collectAsStateWithLifecycle()
    val settings by Graph.settings.state.collectAsStateWithLifecycle()
    val translations by Graph.content.translations.collectAsStateWithLifecycle()
    val favorites by Graph.favorites.list.collectAsStateWithLifecycle()
    val reading by Graph.reading.place.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var view by rememberSaveable { mutableIntStateOf(QuranView.SURAS.ordinal) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val sheets = LocalSheets.current
    val context = LocalContext.current
    val c = content
    val lang = settings.translationFor(uiLanguage)
    // Opened over a text: straight into the search field, the keyboard up.
    val field = remember { FocusRequester() }
    if (onClose != null) LaunchedEffect(c != null) {
        // (The field is laid out with the list: asked again until it is there.)
        if (c != null) repeat(12) {
            if (runCatching { field.requestFocus() }.isSuccess) return@LaunchedEffect
            withFrameNanos { }
        }
    }

    // A result chosen: the keyboard goes (it stayed up over the sūra), then the text opens there.
    val open: (Int, Int?) -> Unit = { sura, aya ->
        keyboard?.hide()
        focus.clearFocus()
        onOpen(sura, aya)
    }

    // Words in Latin letters are looked for in the translation: it is read from the APK once.
    LaunchedEffect(query, lang) {
        if (query.count { it.isLetter() && it < '\u0590' } >= QuranSearch.MIN_LATIN) Graph.content.ensureTranslation(lang)
    }
    LaunchedEffect(c) { if (c != null) withContext(Dispatchers.Default) { c.search.prepare() } }
    val translation = translations[lang]
    val results by produceState<QuranSearch.Results?>(null, c, query, translation) {
        if (c == null || query.isBlank()) {
            value = null
            return@produceState
        }
        delay(SEARCH_DELAY_MS) // while typing
        value = withContext(Dispatchers.Default) { c.search.search(query, translation) }
    }

    Box(Modifier.fillMaxSize().statusBarsPadding()) {
        if (c == null) {
            Loader(Modifier.align(Alignment.Center))
            return@Box
        }
        val playingSura = np?.session?.sura
        LazyColumn(Modifier.fillMaxSize().testTag(if (onClose == null) "quran" else "go")) {
            item(key = "header") {
                if (onClose == null) ScreenHeader(stringResource(R.string.tab_quran))
                else Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBtn(Ic.Close, { keyboard?.hide(); onClose() }, contentDescription = stringResource(R.string.close))
                    Text(stringResource(R.string.quran_go_title), style = Type.name, color = P.text, modifier = Modifier.padding(start = 4.dp))
                }
            }
            item(key = "search") {
                SearchField(
                    query,
                    onChange = { query = it },
                    onDone = { keyboard?.hide() },
                    focus = field,
                    tag = if (onClose == null) "search" else "go-search",
                )
            }
            val found = results
            if (query.isNotBlank()) {
                if (found != null) searchResults(c, found, translations[lang], playingSura, ui.playWhenReady, open)
            } else {
                item(key = "views") {
                    Segmented(
                        options = listOf(
                            stringResource(R.string.quran_view_suras), stringResource(R.string.quran_view_juz),
                            stringResource(R.string.quran_view_hizb), stringResource(R.string.quran_view_favorites),
                        ),
                        selected = view,
                        onSelect = { view = it },
                        modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 6.dp),
                    )
                }
                when (QuranView.entries[view]) {
                    QuranView.SURAS -> {
                        items(c.suras, key = { "s${it.index}" }) { s ->
                            SuraRow(s, playing = playingSura == s.index, active = playingSura == s.index && ui.playWhenReady, readAt = reading?.takeIf { it.sura == s.index && it.aya > 1 }?.aya, onClick = { open(s.index, null) })
                        }
                    }
                    QuranView.JUZ -> itemsIndexed(c.parts.juz, key = { i, _ -> "j$i" }) { i, start ->
                        PartRow(c, stringResource(R.string.quran_juz_n, i + 1), i + 1, start) { open(start.sura, start.aya) }
                    }
                    QuranView.HIZB -> itemsIndexed(c.parts.hizb, key = { i, _ -> "h$i" }) { i, start ->
                        PartRow(c, stringResource(R.string.quran_hizb_n, i + 1), i + 1, start) { open(start.sura, start.aya) }
                    }
                    QuranView.FAVORITES -> {
                        if (favorites.isEmpty()) {
                            item(key = "nofav") {
                                Text(
                                    stringResource(R.string.quran_favorites_empty), style = Type.body, color = P.textDim, textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 36.dp),
                                )
                            }
                        } else {
                            item(key = "favcount") { ListLabel(stringResource(R.string.quran_favorites_count, favorites.size)) }
                            items(favorites, key = { "f${it.sura}:${it.aya}" }) { f ->
                                AyaRow(c, f.ref, arabic(c.text(f.ref)), marked = true) { open(f.sura, f.aya) }
                            }
                        }
                    }
                }
            }
            // (Room under the last row for the bar's button, which floats over this screen's corner.)
            item(key = "end") { Box(Modifier.padding(bottom = 88.dp)) }
        }
    }
}

private const val SEARCH_DELAY_MS = 180L

/** What a search found, by kind: named āyāt, sūras, parts, then the text's and the translation's matches. */
private fun androidx.compose.foundation.lazy.LazyListScope.searchResults(
    c: Content,
    found: QuranSearch.Results,
    translation: app.hfd.core.quran.QuranText?,
    playingSura: Int?,
    playing: Boolean,
    open: (Int, Int?) -> Unit,
) {
    if (found.isEmpty) {
        item(key = "none") {
            Text(
                stringResource(R.string.quran_found_none), style = Type.body, color = P.textDim, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 36.dp).testTag("search-none"),
            )
        }
        return
    }
    if (found.references.isNotEmpty()) {
        item(key = "l-ref") { ListLabel(stringResource(R.string.quran_found_ayat)) }
        items(found.references, key = { "r${it.key}" }) { ref -> AyaRow(c, ref, arabic(c.text(ref))) { open(ref.sura, ref.aya) } }
    }
    if (found.suras.isNotEmpty()) {
        item(key = "l-sura") { ListLabel(stringResource(R.string.quran_found_suras)) }
        items(found.suras, key = { "s$it" }) { n ->
            SuraRow(c.sura(n), playing = playingSura == n, active = playingSura == n && playing, onClick = { open(n, null) })
        }
    }
    if (found.parts.isNotEmpty()) {
        item(key = "l-part") { ListLabel(stringResource(R.string.quran_found_parts)) }
        items(found.parts, key = { (if (it.juz) "pj" else "ph") + it.number }) { p ->
            PartRow(c, stringResource(if (p.juz) R.string.quran_juz_n else R.string.quran_hizb_n, p.number), p.number, p.start) { open(p.start.sura, p.start.aya) }
        }
    }
    if (found.text.isNotEmpty()) {
        item(key = "l-text") {
            ListLabel(
                if (found.textCount > found.text.size) stringResource(R.string.quran_found_text_first, found.text.size, found.textCount)
                else stringResource(R.string.quran_found_text, found.textCount),
            )
        }
        items(found.text, key = { "t${it.ref.key}" }) { hit ->
            AyaRow(c, hit.ref, arabic(c.text(hit.ref), hit.from, hit.to, P.accent)) { open(hit.ref.sura, hit.ref.aya) }
        }
    }
    if (found.translation.isNotEmpty() && translation != null) {
        item(key = "l-trans") {
            ListLabel(
                if (found.translationCount > found.translation.size) stringResource(R.string.quran_found_translation_first, found.translation.size, found.translationCount)
                else stringResource(R.string.quran_found_translation, found.translationCount),
            )
        }
        items(found.translation, key = { "x${it.ref.key}" }) { hit ->
            AyaRow(c, hit.ref, excerpt(translation.text(hit.ref).orEmpty(), hit.from, hit.to, P.accent), latin = true) { open(hit.ref.sura, hit.ref.aya) }
        }
    }
}

@Composable
private fun ListLabel(text: String) {
    SectionLabel(text, Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 4.dp))
}

/** An āya's text for a list: from a little before its words [from]‥[to] (lit in [mark]) when given. */
private fun arabic(text: String, from: Int = -1, to: Int = -1, mark: androidx.compose.ui.graphics.Color? = null): AnnotatedString {
    if (from < 0 || mark == null) return AnnotatedString(text)
    val ranges = Arabic.wordRanges(text)
    if (from !in ranges.indices) return AnnotatedString(text)
    val last = to.coerceIn(from, ranges.lastIndex)
    val start = ranges[(from - BEFORE_WORDS).coerceAtLeast(0)].first
    return buildAnnotatedString {
        if (start > 0) append("… ")
        append(text.substring(start, ranges[from].first))
        withStyle(SpanStyle(color = mark)) { append(text.substring(ranges[from].first, ranges[last].second)) }
        append(text.substring(ranges[last].second))
    }
}

/** A translation around its characters [from] until [to], lit in [mark]. */
private fun excerpt(text: String, from: Int, to: Int, mark: androidx.compose.ui.graphics.Color): AnnotatedString {
    val a = from.coerceIn(0, text.length)
    val b = to.coerceIn(a, text.length)
    // From the start of a word, some forty characters before.
    val start = if (a <= BEFORE_CHARS) 0 else text.indexOf(' ', a - BEFORE_CHARS).let { if (it < 0 || it >= a) a else it + 1 }
    return buildAnnotatedString {
        if (start > 0) append("… ")
        append(text.substring(start, a))
        withStyle(SpanStyle(color = mark, fontWeight = FontWeight.SemiBold)) { append(text.substring(a, b)) }
        append(text.substring(b))
    }
}

private const val BEFORE_WORDS = 3
private const val BEFORE_CHARS = 40

/** An āya in a list (a result, a favourite): where it is, and its text or the translation's passage. */
@Composable
private fun AyaRow(c: Content, ref: AyahRef, text: AnnotatedString, latin: Boolean = false, marked: Boolean = false, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (marked) {
                Icon(Ic.Bookmark, contentDescription = null, tint = P.accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(stringResource(R.string.quran_at, c.sura(ref.sura).tname, ref.aya), style = Type.labelBold, color = P.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(ref.key, style = Type.label, color = P.textFaint, maxLines = 1)
        }
        Spacer(Modifier.height(4.dp))
        if (latin) {
            Text(text, style = Type.translation, color = P.textDim, maxLines = 3, overflow = TextOverflow.Ellipsis)
        } else {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Text(
                    text,
                    style = Type.quran(22).copy(textDirection = TextDirection.Rtl, textAlign = TextAlign.Start),
                    color = P.textDim,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** A juzʾ or a ḥizb: its number in the star, where it begins, and its first words. */
@Composable
private fun PartRow(c: Content, title: String, number: Int, start: AyahRef, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
            Star(Modifier.size(38.dp), color = P.outline, outline = true)
            Text(number.toString(), style = Type.clock.copy(fontSize = 13.sp), color = P.textDim, maxLines = 1)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.name, color = P.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Text(stringResource(R.string.quran_at, c.sura(start.sura).tname, start.aya), style = Type.label, color = P.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        // Its first words, without the quarter's sign Tanzil's text opens it with.
        val words = remember(start) { Arabic.words(c.text(start)).take(4).joinToString(" ") }
        Text(
            words,
            style = Type.arabicTitle.copy(fontSize = 18.sp, lineHeight = 30.sp, textAlign = TextAlign.End, textDirection = TextDirection.Rtl),
            color = P.textDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 150.dp),
        )
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit, onDone: () -> Unit, focus: FocusRequester, tag: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .clip(CircleShape)
            .background(P.surface)
            .border(1.dp, P.outline, CircleShape)
            .padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Ic.Search, contentDescription = null, tint = P.textDim, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).padding(vertical = 11.dp)) {
            val hint = stringResource(R.string.quran_search)
            if (query.isEmpty()) Text(hint, style = Type.body, color = P.textFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = Type.body.copy(color = P.text),
                cursorBrush = SolidColor(P.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onDone() }),
                // The field lies over its hint, which a screen reader then doesn't see: it says it itself.
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag(tag).semantics { contentDescription = hint },
            )
        }
        // (Finding a passage by reciting it is the bar's button, on every screen.)
        if (query.isNotEmpty()) {
            val label = stringResource(R.string.quran_search_clear)
            Box(
                Modifier.size(36.dp).clip(CircleShape).clickable { onChange("") }.semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Ic.Close, contentDescription = null, tint = P.textDim, modifier = Modifier.size(20.dp))
            }
        } else Spacer(Modifier.size(width = 8.dp, height = 36.dp))
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SuraRow(s: SuraMeta, playing: Boolean, active: Boolean, readAt: Int? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Its number in the star, as in a muṣḥaf's margin.
        Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
            Star(Modifier.size(38.dp), color = if (playing) P.accent else P.outline, outline = true)
            if (playing) Equalizer(active, Modifier.size(14.dp))
            else Text(s.index.toString(), style = Type.clock.copy(fontSize = 13.sp), color = P.textDim, maxLines = 1)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(s.tname, style = Type.name, color = P.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            // The sūra being read says where: it opens there.
            if (readAt != null) Text(stringResource(R.string.quran_read_at, readAt, s.ayas), style = Type.label, color = P.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
            else Text(
                stringResource(if (s.type == "Medinan") R.string.sura_medinan else R.string.sura_meccan) + " · " +
                    pluralStringResource(R.plurals.ayat_count, s.ayas, s.ayas),
                style = Type.label,
                color = P.textDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            s.name,
            style = Type.arabicTitle.copy(fontSize = 20.sp, lineHeight = 32.sp, textAlign = TextAlign.End),
            color = if (playing) P.text else P.textDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 140.dp),
        )
    }
}
