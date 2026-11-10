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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import app.hfd.recite.VoiceSearch
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

/**
 * The Qur'an section: every sūra to read and listen to, by sūra, juzʾ or ḥizb; the āyāt kept
 * while reading; and a search of the whole text (a reference, a name, words of the text or of
 * the translation, or a phrase recited aloud). A sūra plays through and the next one follows;
 * "resume" starts again where the listening stopped.
 */
@Composable
fun QuranScreen(onOpen: (sura: Int, aya: Int?) -> Unit) {
    val content by Graph.content.content.collectAsStateWithLifecycle()
    val np by Graph.nowPlaying.collectAsStateWithLifecycle()
    val ui by Graph.player.state.collectAsStateWithLifecycle()
    val place by Graph.quranPlace.place.collectAsStateWithLifecycle()
    val settings by Graph.settings.state.collectAsStateWithLifecycle()
    val translations by Graph.content.translations.collectAsStateWithLifecycle()
    val favorites by Graph.favorites.list.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var view by rememberSaveable { mutableIntStateOf(QuranView.SURAS.ordinal) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val sheets = LocalSheets.current
    val context = LocalContext.current
    val c = content
    val lang = settings.translationFor(uiLanguage)

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
        LazyColumn(Modifier.fillMaxSize().testTag("quran")) {
            item(key = "header") { ScreenHeader(stringResource(R.string.tab_quran)) }
            // Where the listening stopped: the sūra playing now, else the last place.
            val at = np?.takeIf { playingSura != null }?.item?.ref ?: place
            if (at != null && query.isBlank()) {
                item(key = "resume") {
                    val meta = c.sura(at.sura)
                    val playing = playingSura == at.sura && ui.playWhenReady
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .card()
                            .clickable { open(at.sura, at.aya) }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(if (playing) R.string.quran_listening else R.string.quran_resume).uppercase(), style = Type.overline, color = P.textDim)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                stringResource(R.string.quran_resume_at, meta.tname, at.aya, meta.ayas),
                                style = Type.name,
                                color = P.text,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        PillButton(
                            stringResource(if (playing) R.string.player_pause else R.string.player_resume),
                            onClick = { if (playingSura == at.sura) Graph.player.togglePlay() else Graph.playSura(at.sura, at) },
                            style = if (playing) PillStyle.Outline else PillStyle.Filled,
                        )
                    }
                }
            }
            item(key = "search") {
                SearchField(
                    query,
                    onChange = { query = it },
                    onDone = { keyboard?.hide() },
                    onVoice = {
                        keyboard?.hide()
                        focus.clearFocus()
                        sheets(
                            SheetSpec(
                                title = context.getString(R.string.quran_voice_title),
                                content = { VoiceSearchSheet(c, onOpen = { ref -> open(ref.sura, ref.aya) }) },
                            ),
                        )
                    },
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
                        item(key = "count") { ListLabel(stringResource(R.string.quran_count, c.suras.size)) }
                        items(c.suras, key = { "s${it.index}" }) { s ->
                            SuraRow(s, playing = playingSura == s.index, active = playingSura == s.index && ui.playWhenReady, onClick = { open(s.index, null) })
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
            item(key = "end") { Box(Modifier.padding(bottom = 24.dp)) }
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
private fun SearchField(query: String, onChange: (String) -> Unit, onDone: () -> Unit, onVoice: () -> Unit) {
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
            if (query.isEmpty()) Text(stringResource(R.string.quran_search), style = Type.body, color = P.textFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = Type.body.copy(color = P.text),
                cursorBrush = SolidColor(P.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onDone() }),
                modifier = Modifier.fillMaxWidth().testTag("search"),
            )
        }
        val label = stringResource(if (query.isNotEmpty()) R.string.quran_search_clear else R.string.quran_voice)
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable { if (query.isNotEmpty()) onChange("") else onVoice() }
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (query.isNotEmpty()) Ic.Close else Ic.Mic, contentDescription = null, tint = if (query.isNotEmpty()) P.textDim else P.accent, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * The search by reciting, in a sheet: it listens at once (asking for the microphone first), shows
 * what it hears and where that is in the text; a place chosen opens there.
 */
@Composable
private fun VoiceSearchSheet(c: Content, onOpen: (AyahRef) -> Unit) {
    val close = LocalSheetClose.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val whisper by Graph.speech.state.collectAsStateWithLifecycle()
    val ctc by Graph.speechCtc.state.collectAsStateWithLifecycle()
    val ready = whisper is ModelState.Ready || ctc is ModelState.Ready
    if (!ready) {
        // No model on the phone yet: the one Recite uses, downloaded here as there.
        val mb = (Graph.speechCtc.model.bytes / 1_000_000).toInt()
        Text(stringResource(R.string.quran_voice_model, mb), style = Type.body, color = P.text)
        Spacer(Modifier.height(12.dp))
        when (val state = ctc) {
            is ModelState.Downloading -> Text(stringResource(R.string.recite_downloading, (state.progress * 100).toInt()), style = Type.labelBold, color = P.textDim)
            else -> {
                if (state is ModelState.Failed) {
                    Text(stringResource(R.string.recite_download_failed, state.message), style = Type.label, color = P.error)
                    Spacer(Modifier.height(8.dp))
                }
                PillButton(stringResource(R.string.recite_download, mb), { Graph.speechCtc.download() }, style = PillStyle.Accent)
            }
        }
        Spacer(Modifier.height(8.dp))
        return
    }
    val voice = remember { VoiceSearch(scope, Graph::reciteModels) { heard -> c.search.recited(heard) } }
    DisposableEffect(voice) { onDispose { voice.close() } }
    val ui by voice.ui.collectAsStateWithLifecycle()
    val wave by voice.wave.collectAsStateWithLifecycle()
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) voice.start() else Graph.toast(R.string.recite_mic_denied)
    }
    fun listen() {
        // What plays would be heard by the microphone.
        if (Graph.player.state.value.playWhenReady) Graph.player.pause()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) voice.start()
        else mic.launch(Manifest.permission.RECORD_AUDIO)
    }
    LaunchedEffect(voice) { listen() }

    Text(
        when {
            ui.error == ReciteSession.ERROR_MODEL -> stringResource(R.string.recite_unavailable)
            ui.error == ReciteSession.ERROR_MIC -> stringResource(R.string.recite_mic_denied)
            ui.loading -> stringResource(R.string.recite_loading)
            ui.listening && ui.heard.isBlank() -> stringResource(R.string.quran_voice_hint)
            ui.listening -> stringResource(R.string.quran_voice_listening)
            ui.hits.isNotEmpty() -> ""
            ui.heard.isBlank() -> stringResource(R.string.quran_voice_silent)
            else -> stringResource(R.string.quran_voice_none)
        },
        style = Type.body,
        color = P.textDim,
        modifier = Modifier.testTag("voice-status"),
    )
    if (ui.listening) {
        Spacer(Modifier.height(10.dp))
        Waveform(wave)
    }
    if (ui.heard.isNotBlank()) {
        Spacer(Modifier.height(8.dp))
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Text(
                ui.heard, style = Type.quran(20).copy(textDirection = TextDirection.Rtl), color = P.textFaint, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    Spacer(Modifier.height(6.dp))
    for (hit in ui.hits) {
        val text = c.text(hit.ref)
        // From the word the phrase begins at.
        val from = hit.word.takeIf { it > 0 } ?: -1
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable {
                    close()
                    onOpen(hit.ref)
                }
                .padding(horizontal = 6.dp, vertical = 10.dp),
        ) {
            Text(
                if (hit.last.sura == hit.ref.sura && hit.last.aya > hit.ref.aya) stringResource(R.string.quran_voice_to, c.sura(hit.ref.sura).tname, hit.ref.aya, hit.last.aya)
                else stringResource(R.string.quran_at, c.sura(hit.ref.sura).tname, hit.ref.aya),
                style = Type.labelBold,
                color = P.text,
            )
            Spacer(Modifier.height(2.dp))
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Text(
                    if (from > 0) arabic(text, from, Int.MAX_VALUE, P.textDim) else AnnotatedString(text),
                    style = Type.quran(22).copy(textDirection = TextDirection.Rtl),
                    color = P.textDim,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    if (ui.listening) PillButton(stringResource(R.string.quran_voice_stop), { voice.stop() }, icon = Ic.Stop)
    else if (ui.done) PillButton(stringResource(R.string.quran_voice_again), { listen() }, icon = Ic.Mic, style = if (ui.hits.isEmpty()) PillStyle.Accent else PillStyle.Outline)
    Spacer(Modifier.height(8.dp))
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SuraRow(s: SuraMeta, playing: Boolean, active: Boolean, onClick: () -> Unit) {
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
            Text(
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
