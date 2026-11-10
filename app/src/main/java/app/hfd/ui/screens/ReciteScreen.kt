package app.hfd.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hfd.Graph
import app.hfd.R
import app.hfd.core.playback.QuranListening
import app.hfd.core.recite.AyahResult
import app.hfd.core.recite.ReciteTarget
import app.hfd.core.recite.WordStatus
import app.hfd.recite.ModelState
import app.hfd.recite.ModelStore
import app.hfd.recite.ReciteEngine
import app.hfd.recite.ReciteSession
import app.hfd.recite.ReciteUi
import app.hfd.recite.Wave
import app.hfd.ui.ayahEnd
import app.hfd.ui.components.KeepScreenOn
import app.hfd.ui.components.Ic
import app.hfd.ui.components.IconBtn
import app.hfd.ui.components.PillButton
import app.hfd.ui.components.PillStyle
import app.hfd.ui.components.SectionLabel
import app.hfd.ui.components.card
import app.hfd.ui.text
import app.hfd.ui.theme.P
import app.hfd.ui.theme.Type

/** Reciting a passage aloud from memory: words light up as they are recited, mistakes in red. */
@Composable
fun ReciteScreen(onClose: () -> Unit) {
    val session by Graph.recite.collectAsStateWithLifecycle()
    val s = session
    if (s == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val ui by s.ui.collectAsStateWithLifecycle()
    val content by Graph.content.content.collectAsStateWithLifecycle()
    val settings by Graph.settings.state.collectAsStateWithLifecycle()
    val whisperModel by Graph.speech.state.collectAsStateWithLifecycle()
    val ctcModel by Graph.speechCtc.state.collectAsStateWithLifecycle()
    // The CTC model is the one to have unless the owner keeps to Whisper; Whisper reads until it is there.
    val best = settings.reciteEngine == ReciteEngine.BEST
    val ready = whisperModel is ModelState.Ready || (best && ctcModel is ModelState.Ready)
    val level by s.level.collectAsStateWithLifecycle()
    val wave by s.wave.collectAsStateWithLifecycle()
    val listeningWith by s.mic.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val showText = settings.reciteShowText
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) s.start() else Graph.toast(R.string.recite_mic_denied)
    }
    fun listen() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) s.start()
        else mic.launch(Manifest.permission.RECORD_AUDIO)
    }
    BackHandler(onBack = onClose)

    // The screen stays on while the verses are shown (listening or not).
    KeepScreenOn()

    // A passage, or a sūra of the Qur'an section.
    val name = QuranListening.suraOf(s.fadilaId)?.let { n -> content?.suras?.getOrNull(n - 1)?.tname } ?: content?.fadila(s.fadilaId)?.title?.text
    Column(Modifier.fillMaxSize().background(P.background).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Ic.Close, onClose, contentDescription = stringResource(R.string.close))
            Text(
                name?.let { stringResource(R.string.recite_title, it) }.orEmpty(),
                style = Type.name,
                color = P.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            // A sūra of the Qur'an section has two modes: this one, and reading (back to its text).
            if (QuranListening.suraOf(s.fadilaId) != null) {
                ModeSwitch(reciting = true, onRead = onClose, onRecite = {}, modifier = Modifier.padding(end = 8.dp))
            }
        }
        Text(statusLine(ui, s), style = Type.label, color = P.textDim, modifier = Modifier.padding(horizontal = 20.dp))
        ProgressLine(ui, Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        when {
            !best -> ModelCard(Graph.speech, whisperModel, R.string.recite_model_needed)
            whisperModel is ModelState.Ready -> ModelCard(Graph.speechCtc, ctcModel, R.string.recite_model_upgrade, keepOld = true)
            else -> ModelCard(Graph.speechCtc, ctcModel, R.string.recite_model_ctc_needed)
        }

        val list = rememberLazyListState()
        // The āya being recited comes into view when it isn't (its word then keeps itself there,
        // AyahWords: a long āya runs over more lines than the screen holds).
        LaunchedEffect(ui.next?.first) {
            val a = ui.next?.first ?: return@LaunchedEffect
            if (list.layoutInfo.visibleItemsInfo.none { it.index == a }) list.animateScrollToItem(a)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list) {
            itemsIndexed(ui.targets) { a, t ->
                AyahWords(t, ui.status.getOrNull(a).orEmpty(), current = ui.next?.takeIf { it.first == a }?.second, showText, settings.arabicSize)
            }
            if (ui.done && ui.results.isNotEmpty()) item { Summary(ui.results, onAgain = { Graph.openRecite(s.fadilaId, ui.targets.map { t -> t.ref }) }, onClose) }
        }

        if (ui.lost && ui.listening && !ui.done) {
            // Phrases heard that aren't the text from the marker: where to take it up again.
            Text(
                stringResource(if (showText) R.string.recite_lost else R.string.recite_lost_hidden), style = Type.label, color = P.wordMark, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            )
        } else ui.heard?.let {
            Text(it, style = Type.label, color = P.textFaint, maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp))
        }
        if (ui.muffled && ui.listening) {
            Text(
                stringResource(R.string.recite_muffled), style = Type.label, color = P.saveOrange, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        if (ui.listening) Waveform(wave, Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
        listeningWith?.let { m ->
            // Which microphone listens; with earbuds connected, a tap switches (listening goes on).
            val switchable = m.headset || m.headsetAvailable
            Text(
                when {
                    m.headset -> stringResource(R.string.recite_mic_headset, m.name ?: stringResource(R.string.recite_mic_earbuds))
                    m.headsetAvailable -> stringResource(R.string.recite_mic_phone_switch)
                    else -> stringResource(R.string.recite_mic_phone)
                },
                style = Type.label, color = P.textDim, textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (switchable) Modifier.clickable {
                            Graph.settings.update { it.copy(reciteHeadset = !m.headset) }
                            s.restartListening()
                        } else Modifier,
                    )
                    .padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconBtn(Ic.Hint, { s.hint() }, bordered = true, size = 48.dp, contentDescription = stringResource(R.string.recite_hint))
            MicButton(listening = ui.listening, level = level, enabled = ready && !ui.done) {
                if (ui.listening) s.stop() else listen()
            }
            IconBtn(
                if (showText) Ic.Hidden else Ic.Visible, { Graph.settings.update { it.copy(reciteShowText = !it.reciteShowText) } }, bordered = true, size = 48.dp,
                contentDescription = stringResource(if (showText) R.string.recite_hide_text else R.string.recite_show_text),
            )
        }
    }
}

@Composable
private fun statusLine(ui: ReciteUi, s: ReciteSession): String {
    val next = ui.next
    return when {
        ui.error == ReciteSession.ERROR_MODEL -> stringResource(R.string.recite_unavailable)
        ui.error == ReciteSession.ERROR_MIC -> stringResource(R.string.recite_mic_denied)
        ui.loading -> stringResource(R.string.recite_loading)
        next == null -> stringResource(R.string.recite_done)
        ui.pending > 0 -> stringResource(R.string.recite_recognising)
        ui.listening -> stringResource(R.string.recite_listening)
        else -> stringResource(R.string.recite_position, next.first + 1, s.targets.size, s.targets[next.first].ref.toString())
    }
}

/**
 * Download card until [store]'s speech model is on the phone ([state]). With [keepOld] the model
 * is an upgrade (Whisper reads meanwhile): the card also offers to keep to Whisper.
 */
@Composable
private fun ModelCard(store: ModelStore, state: ModelState, text: Int, keepOld: Boolean = false) {
    if (state is ModelState.Ready) return
    val mb = (store.model.bytes / 1_000_000).toInt()
    Column(
        Modifier.fillMaxWidth().padding(16.dp).card().padding(16.dp),
    ) {
        Text(stringResource(text, mb), style = Type.body, color = P.text)
        Spacer(Modifier.height(10.dp))
        if (state is ModelState.Downloading) {
            Text(stringResource(R.string.recite_downloading, (state.progress * 100).toInt()), style = Type.labelBold, color = P.textDim)
        } else {
            if (state is ModelState.Failed) {
                Text(stringResource(R.string.recite_download_failed, state.message), style = Type.label, color = P.error)
                Spacer(Modifier.height(8.dp))
            }
            PillButton(stringResource(R.string.recite_download, mb), { store.download() }, style = PillStyle.Accent)
            if (keepOld) {
                Spacer(Modifier.height(8.dp))
                PillButton(stringResource(R.string.recite_model_keep), { Graph.settings.update { it.copy(reciteEngine = ReciteEngine.WHISPER) } })
            }
        }
    }
}

/**
 * An āya's words, right to left: dim until recited (or hidden, without [showText]), then in full
 * colour; mistakes in red, hints in yellow. The word to say next is in the marker's turquoise.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun AyahWords(t: ReciteTarget, status: List<WordStatus>, current: Int?, showText: Boolean, size: Int) {
    val style = Type.quran(size)
    // The word to say next: turquoise on a pill of the same colour, as the word being recited
    // while listening (QuranText); hidden text keeps the pill only.
    val mark = P.wordMark
    // The marker's word stays in view with a line and a half around it, like a prompter: the
    // list scrolls as the recitation goes down a long āya (Āyat al-Kursī is ten lines).
    val inView = remember { BringIntoViewRequester() }
    // (The size of the last word marked: the lines are all as high.)
    val markSize = remember { arrayOf(IntSize.Zero) }
    LaunchedEffect(current) {
        if (current == null) return@LaunchedEffect
        val (w, h) = markSize[0].let { it.width.toFloat() to it.height.toFloat() }
        inView.bringIntoView(if (h > 0f) Rect(0f, -1.5f * h, w, 2.5f * h) else null)
    }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            t.words.forEachIndexed { w, word ->
                val st = status.getOrElse(w) { WordStatus.PENDING }
                // Also on a word recited before: the reciter is saying it again.
                val next = w == current
                val hidden = st == WordStatus.PENDING && !showText
                val color = when {
                    hidden -> Color.Transparent
                    next -> mark
                    else -> when (st) {
                        WordStatus.PENDING -> P.textFaint
                        WordStatus.OK -> P.text
                        WordStatus.WRONG, WordStatus.MISSED -> P.error
                        WordStatus.HINTED -> P.saveYellow
                    }
                }
                // A word lights up (or the marker moves onto it) over a moment, not at a stroke.
                val shown by animateColorAsState(color, tween(if (hidden) 0 else 160), label = "word")
                val marked by animateFloatAsState(if (next) 1f else 0f, tween(140), label = "marker")
                Text(
                    word,
                    style = style.copy(textDecoration = if (st == WordStatus.MISSED) TextDecoration.LineThrough else null),
                    color = shown,
                    modifier = Modifier
                        .then(if (next) Modifier.bringIntoViewRequester(inView).onSizeChanged { markSize[0] = it } else Modifier)
                        .then(if (hidden) Modifier.clip(RoundedCornerShape(8.dp)).background(P.surfaceHigh) else Modifier)
                        // Drawn as QuranText draws it: hugging the letters, a little wider than
                        // the word, without moving the words around it.
                        .drawBehind {
                            if (marked <= 0f) return@drawBehind
                            val box = this.size
                            val inset = box.height * 0.12f
                            val pad = 2.dp.toPx()
                            drawRoundRect(
                                mark.copy(alpha = (if (hidden) 0.45f else 0.16f) * marked),
                                topLeft = Offset(-pad, inset),
                                size = Size(box.width + 2 * pad, box.height - 2 * inset),
                                cornerRadius = CornerRadius(10.dp.toPx()),
                            )
                        }
                        .padding(horizontal = 2.dp),
                )
            }
            // The āya's end says how it went once all of it is recited: gold, or red with a mistake.
            val recited = status.size >= t.words.size && status.none { it == WordStatus.PENDING }
            val end by animateColorAsState(
                when {
                    !recited -> P.textDim
                    status.any { it == WordStatus.WRONG || it == WordStatus.MISSED } -> P.error
                    status.any { it == WordStatus.HINTED } -> P.saveYellow
                    else -> P.accent
                },
                tween(300), label = "end",
            )
            Text(ayahEnd(t.ref.aya), style = style, color = end)
        }
    }
}

/** How far the recitation is: a thin bar of the words recited, and the mistakes so far. */
@Composable
private fun ProgressLine(ui: ReciteUi, modifier: Modifier = Modifier) {
    val total = remember(ui.targets) { ui.targets.sumOf { it.words.size } }
    val recited = ui.status.sumOf { a -> a.count { it != WordStatus.PENDING } }
    val mistakes = ui.status.sumOf { a -> a.count { it == WordStatus.WRONG || it == WordStatus.MISSED || it == WordStatus.HINTED } }
    val fraction by animateFloatAsState(if (total == 0) 0f else recited.toFloat() / total, tween(250), label = "progress")
    val track = P.track
    val bar = P.accent
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.weight(1f).height(4.dp)) {
            val r = CornerRadius(size.height / 2)
            drawRoundRect(track, cornerRadius = r)
            // From the right, as the text is read.
            val w = size.width * fraction
            if (w > 0f) drawRoundRect(bar, topLeft = Offset(size.width - w, 0f), size = Size(w, size.height), cornerRadius = r)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            if (mistakes == 0) stringResource(R.string.recite_progress, recited, total)
            else pluralStringResource(R.plurals.recite_progress_mistakes, mistakes, recited, total, mistakes),
            style = Type.label, color = if (mistakes == 0) P.textDim else P.error,
        )
    }
}

@Composable
private fun Summary(results: List<AyahResult>, onAgain: () -> Unit, onClose: () -> Unit) {
    val targets = Graph.recite.value?.targets.orEmpty()
    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        SectionLabel(stringResource(R.string.recite_done))
        Spacer(Modifier.height(8.dp))
        val words = results.sumOf { it.words }
        val right = words - results.sumOf { it.mistakes.size }
        Text(
            stringResource(R.string.recite_total, right, words, if (words == 0) 100 else right * 100 / words),
            style = Type.displaySmall, color = if (right == words) P.accent else P.text,
        )
        Spacer(Modifier.height(10.dp))
        results.forEach { r ->
            Text(
                stringResource(R.string.recite_result, r.ref.toString(), r.words - r.mistakes.size, r.words),
                style = Type.title,
                color = if (r.mistakes.isEmpty()) P.text else P.error,
            )
            val words = targets.firstOrNull { it.ref == r.ref }?.words.orEmpty()
            if (r.mistakes.isNotEmpty()) {
                Text(
                    stringResource(R.string.recite_mistakes, r.mistakes.mapNotNull { words.getOrNull(it) }.joinToString("، ")),
                    style = Type.body,
                    color = P.textDim,
                )
            }
            Spacer(Modifier.height(6.dp))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(stringResource(R.string.recite_again), onAgain, style = PillStyle.Filled)
            PillButton(stringResource(R.string.close), onClose)
        }
    }
}

/**
 * What the microphone hears, like a voice message being recorded: the last seconds as bars,
 * newest on the right; bright where it counts as voice, dim where it's the room.
 */
@Composable
internal fun Waveform(wave: Wave, modifier: Modifier = Modifier) {
    val voice = P.accent
    val room = P.textFaint
    Canvas(modifier.fillMaxWidth().height(40.dp)) {
        val bar = 3.dp.toPx()
        val step = bar + 3.dp.toPx()
        val n = wave.levels.size
        val count = minOf(n, (size.width / step).toInt())
        for (k in 0 until count) {
            val i = n - 1 - k
            val h = maxOf(bar, wave.levels[i] * size.height)
            drawRoundRect(
                color = if (wave.voiced[i]) voice else room,
                topLeft = Offset(size.width - (k + 1) * step, (size.height - h) / 2),
                size = Size(bar, h),
                cornerRadius = CornerRadius(bar / 2),
            )
        }
    }
}

/** Round microphone button; its ring follows the voice level while listening. */
@Composable
private fun MicButton(listening: Boolean, level: Float, enabled: Boolean, onClick: () -> Unit) {
    val ring = if (listening) 4.dp + (level * 10).dp else 1.dp
    Box(
        Modifier
            .size(76.dp)
            .clip(CircleShape)
            .border(ring, if (listening) P.accent.copy(alpha = 0.45f) else if (enabled) P.primary else P.outline, CircleShape)
            .background(if (listening) P.accent else if (enabled) P.primary else P.surfaceHigh)
            .clickable(enabled = enabled || listening, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (listening) Ic.Stop else Ic.Mic,
            contentDescription = stringResource(if (listening) R.string.recite_stop else R.string.recite_start),
            tint = if (listening) P.background else if (enabled) P.onPrimary else P.textFaint,
            modifier = Modifier.size(32.dp),
        )
    }
}
