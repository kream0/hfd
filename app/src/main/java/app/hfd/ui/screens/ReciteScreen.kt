package app.hfd.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hfd.Graph
import app.hfd.R
import app.hfd.core.recite.AyahResult
import app.hfd.core.recite.ReciteTarget
import app.hfd.core.recite.WordStatus
import app.hfd.recite.ModelState
import app.hfd.recite.ReciteSession
import app.hfd.recite.ReciteUi
import app.hfd.recite.Wave
import app.hfd.ui.ayahEnd
import app.hfd.ui.components.Ic
import app.hfd.ui.components.IconBtn
import app.hfd.ui.components.PillButton
import app.hfd.ui.components.PillStyle
import app.hfd.ui.components.SectionLabel
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
    val model by Graph.speech.state.collectAsStateWithLifecycle()
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

    // The screen stays on while listening.
    val view = LocalView.current
    DisposableEffect(ui.listening) {
        view.keepScreenOn = ui.listening
        onDispose { view.keepScreenOn = false }
    }

    val f = content?.fadila(s.fadilaId)
    Column(Modifier.fillMaxSize().background(P.background).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Ic.Close, onClose, contentDescription = stringResource(R.string.close))
            Text(
                f?.let { stringResource(R.string.recite_title, it.title.text) }.orEmpty(),
                style = Type.title,
                color = P.text,
                maxLines = 1,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
        }
        Text(statusLine(ui, s), style = Type.label, color = P.textDim, modifier = Modifier.padding(horizontal = 20.dp))
        ModelCard(model)

        val list = rememberLazyListState()
        // Keep the āya being recited in view.
        LaunchedEffect(ui.next?.first) { ui.next?.first?.let { list.animateScrollToItem(it) } }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list) {
            itemsIndexed(ui.targets) { a, t ->
                AyahWords(t, ui.status.getOrNull(a).orEmpty(), current = ui.next?.takeIf { it.first == a }?.second, showText, settings.arabicSize, ui.listening)
            }
            if (ui.done && ui.results.isNotEmpty()) item { Summary(ui.results, onAgain = { f?.let { Graph.openRecite(it.id, ui.targets.map { t -> t.ref }) } }, onClose) }
        }

        ui.heard?.let {
            Text(it, style = Type.label, color = P.textFaint, maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp))
        }
        if (ui.muffled && ui.listening) {
            Text(
                stringResource(R.string.recite_muffled), style = Type.label, color = P.accent, textAlign = TextAlign.Center,
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
            MicButton(listening = ui.listening, level = level, enabled = model is ModelState.Ready && !ui.done) {
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
    }.uppercase()
}

/** Download card until the speech model is on the phone. */
@Composable
private fun ModelCard(model: ModelState) {
    if (model is ModelState.Ready) return
    val mb = (Graph.speech.model.bytes / 1_000_000).toInt()
    Column(
        Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(20.dp)).background(P.surface).padding(16.dp),
    ) {
        Text(stringResource(R.string.recite_model_needed, mb), style = Type.body, color = P.text)
        Spacer(Modifier.height(10.dp))
        when (model) {
            is ModelState.Downloading -> Text(stringResource(R.string.recite_downloading, (model.progress * 100).toInt()), style = Type.labelBold, color = P.textDim)
            is ModelState.Failed -> {
                Text(stringResource(R.string.recite_download_failed, model.message), style = Type.label, color = P.accent)
                Spacer(Modifier.height(8.dp))
                PillButton(stringResource(R.string.recite_download, mb), { Graph.speech.download() }, style = PillStyle.Accent)
            }
            else -> PillButton(stringResource(R.string.recite_download, mb), { Graph.speech.download() }, style = PillStyle.Accent)
        }
    }
}

/**
 * An āya's words, right to left: dim until recited (or hidden, without [showText]), then in full
 * colour; mistakes in red, hints in yellow. The next word to say is a little brighter, and
 * breathes while listening.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AyahWords(t: ReciteTarget, status: List<WordStatus>, current: Int?, showText: Boolean, size: Int, listening: Boolean) {
    val style = Type.quran(size)
    val breath = if (current != null && listening) {
        rememberInfiniteTransition(label = "next").animateFloat(
            0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "breath",
        ).value
    } else 0.6f
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            t.words.forEachIndexed { w, word ->
                val st = status.getOrElse(w) { WordStatus.PENDING }
                val next = w == current
                val hidden = st == WordStatus.PENDING && !showText
                val color = when (st) {
                    WordStatus.PENDING -> when {
                        hidden -> Color.Transparent
                        next -> lerp(P.textFaint, P.text, breath)
                        else -> P.textFaint
                    }
                    WordStatus.OK -> P.text
                    WordStatus.WRONG, WordStatus.MISSED -> P.accent
                    WordStatus.HINTED -> P.saveYellow
                }
                Text(
                    word,
                    style = style.copy(textDecoration = if (st == WordStatus.MISSED) TextDecoration.LineThrough else null),
                    color = color,
                    modifier = Modifier
                        .then(
                            if (hidden) Modifier.clip(RoundedCornerShape(8.dp)).background(if (next) lerp(P.surfaceHigh, P.outline, breath) else P.surfaceHigh)
                            else Modifier,
                        )
                        .padding(horizontal = 2.dp),
                )
            }
            Text(ayahEnd(t.ref.aya), style = style, color = P.textDim)
        }
    }
}

@Composable
private fun Summary(results: List<AyahResult>, onAgain: () -> Unit, onClose: () -> Unit) {
    val targets = Graph.recite.value?.targets.orEmpty()
    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        SectionLabel(stringResource(R.string.recite_done))
        Spacer(Modifier.height(8.dp))
        results.forEach { r ->
            Text(
                stringResource(R.string.recite_result, r.ref.toString(), r.words - r.mistakes.size, r.words),
                style = Type.title,
                color = if (r.mistakes.isEmpty()) P.text else P.accent,
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
private fun Waveform(wave: Wave, modifier: Modifier = Modifier) {
    val voice = P.text
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
            .border(ring, if (listening) P.accent else P.outline, CircleShape)
            .background(if (listening) P.accent else if (enabled) P.inverse else P.surfaceHigh)
            .clickable(enabled = enabled || listening, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (listening) Ic.Stop else Ic.Mic,
            contentDescription = stringResource(if (listening) R.string.recite_stop else R.string.recite_start),
            tint = if (listening) Color.White else if (enabled) P.onInverse else P.textFaint,
            modifier = Modifier.size(32.dp),
        )
    }
}
