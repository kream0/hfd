package app.hfd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hfd.Graph
import app.hfd.R
import app.hfd.core.fadail.Fadila
import app.hfd.core.playback.GapMode
import app.hfd.core.playback.PlanItem
import app.hfd.core.playback.PlanSpec
import app.hfd.core.playback.QuranListening
import app.hfd.core.playback.Reciter
import app.hfd.core.playback.Reciters
import app.hfd.core.quran.AyahRef
import app.hfd.data.AppSettings
import app.hfd.data.SubRange
import app.hfd.download.DlStatus
import app.hfd.playback.NowPlaying
import app.hfd.playback.PlaySession
import app.hfd.playback.Sleep
import app.hfd.ui.text
import app.hfd.ui.theme.P
import app.hfd.ui.theme.Type

fun timesLabel(n: Int): String = if (n == PlanSpec.INFINITE) "∞" else n.toString()

fun gapLabel(g: GapMode): String = when (g) {
    GapMode.NONE -> "0"
    GapMode.HALF -> "½×"
    GapMode.ONE -> "1×"
    GapMode.ONE_HALF -> "1½×"
}

fun speedLabel(s: Float): String = if (s == 1f) "1×" else "${"%.2f".format(java.util.Locale.US, s).trimEnd('0').trimEnd('.')}×"

/** "2:255 · 3/5" for the playing item. */
@Composable
fun itemLabel(item: PlanItem?): String = when (item) {
    null -> ""
    is PlanItem.Basmala -> stringResource(R.string.basmala_label)
    is PlanItem.Ayah -> item.ref.toString() + if (item.of == 1) "" else " · ${item.rep}/${timesLabel(item.of)}"
    is PlanItem.Gap -> item.ref.toString() + " · ${item.cursor.rep} · …"
}

/** "18:1–10", "2:255", or "All" for the whole faḍīla. */
@Composable
fun rangeLabel(f: Fadila, r: SubRange): String {
    val ayat = f.ayat
    if (r.from == 0 && r.to == ayat.lastIndex) return stringResource(R.string.chip_all)
    val a = ayat[r.from]
    val b = ayat[r.to]
    return when {
        a == b -> a.toString()
        a.sura == b.sura -> "$a–${b.aya}"
        else -> "$a–$b"
    }
}

fun sessionFor(f: Fadila, title: String, r: SubRange): PlaySession =
    PlaySession(f.id, title, f.ayat.subList(r.from, r.to + 1), r.from, r.to)

/** Offline state of a faḍīla's āyāt on the red → orange → yellow → green scale. */
@Composable
fun OfflineChip(f: Fadila, modifier: Modifier = Modifier) {
    val settings by Graph.settings.state.collectAsStateWithLifecycle()
    OfflineChip(settings.reciterInfo, neededAudio(f, settings), modifier)
}

/**
 * The same for [refs] as [reciter] recites them; a tap saves what is missing. [optional]: nothing
 * is saved unless asked (a sūra of the Qur'an section streams), so "not saved" is an offer, in grey.
 */
@Composable
fun OfflineChip(reciter: Reciter, refs: List<AyahRef>, modifier: Modifier = Modifier, optional: Boolean = false) {
    val files by Graph.audio.files.collectAsStateWithLifecycle()
    val tasks by Graph.downloads.tasks.collectAsStateWithLifecycle()
    val progress = Graph.downloads.progress(reciter, refs, files, tasks)
    val keys = refs.map { Graph.audio.key(reciter, it) }
    val failed = keys.any { tasks[it]?.status == DlStatus.FAILED }
    val waiting = keys.any { tasks[it]?.status == DlStatus.WAITING_NETWORK }
    val active = keys.any { tasks[it] != null }
    // (Under a hundredth: the basmala alone may be there, saved with a passage.)
    val offer = optional && progress < 0.01f && !active
    val color = if (offer) P.textDim else P.saveColor(progress)
    val label = when {
        offer -> stringResource(R.string.offline_save)
        progress >= 1f -> stringResource(R.string.offline_saved)
        failed -> stringResource(R.string.offline_failed)
        waiting -> stringResource(R.string.offline_waiting)
        active -> stringResource(R.string.offline_saving, (progress * 100).toInt())
        else -> stringResource(R.string.offline_partial, (progress * 100).toInt())
    }
    Row(
        modifier
            .clip(CircleShape)
            .border(1.dp, if (offer) P.outline else color, CircleShape)
            .clickable(enabled = progress < 1f) { Graph.downloads.ensure(reciter, refs) }
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (progress >= 1f) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
        } else if (!offer) {
            ProgressRing(progress, Modifier.size(12.dp), color = color, spinning = waiting)
            Spacer(Modifier.width(6.dp))
        }
        Text(label, style = Type.labelBold, color = color, maxLines = 1)
    }
}

/** The āya files a faḍīla needs with the current settings (its āyāt, plus the basmala). */
fun neededAudio(f: Fadila, s: AppSettings): List<AyahRef> {
    val needsBasmala = s.basmala && f.ranges.any { it.from == 1 && it.sura != 1 && it.sura != 9 }
    return if (needsBasmala) f.ayat + app.hfd.core.playback.EveryAyah.BASMALA else f.ayat
}

/**
 * What opening a faḍīla downloads by itself: everything, except for long sūras (al-Baqara is
 * ~90 MB), where only the chosen range, or the first [LONG_PREFETCH] āyāt, are fetched.
 */
fun autoAudio(f: Fadila, s: AppSettings): List<AyahRef> {
    val all = neededAudio(f, s)
    if (!f.isLong) return all
    val r = Graph.ranges.get(f.id, f.size)
    val whole = r.from == 0 && r.to == f.ayat.lastIndex
    val part = if (whole) f.ayat.take(LONG_PREFETCH) else f.ayat.subList(r.from, r.to + 1)
    return part + all.filter { it == app.hfd.core.playback.EveryAyah.BASMALA }
}

private const val LONG_PREFETCH = 10

/** Whether the player holds [id] as its own screen plays it: not one āya of it a lesson or a review last played (their sessions carry a tag). */
fun NowPlaying.plays(id: String): Boolean = fadilaId == id && session.tag == null

/** What listening to a text is, for the bar and the full player: a passage, or a sūra of the Qur'an section. */
private class Listening(val id: String, val title: String, val fadila: Fadila?, val sura: Int?, val ayat: List<AyahRef>)

@Composable
private fun listening(id: String): Listening? {
    val content by Graph.content.content.collectAsStateWithLifecycle()
    val c = content ?: return null
    val sura = QuranListening.suraOf(id)
    if (sura != null) {
        val meta = c.suras.getOrNull(sura - 1) ?: return null
        return remember(sura, meta) { Listening(id, meta.tname, null, sura, QuranListening.ayat(sura, meta.ayas)) }
    }
    val f = c.fadila(id) ?: return null
    val title = f.title.text
    return remember(f, title) { Listening(id, title, f, null, f.ayat) }
}

/** Starts listening to [l] as its screen does: a sūra from the āya at the top of its text (where it is being read), a passage's chosen range. */
private fun start(l: Listening) {
    val f = l.fadila
    if (f != null) Graph.player.play(sessionFor(f, l.title, Graph.ranges.get(f.id, f.size)))
    else l.sura?.let { n -> Graph.playSura(n, Graph.reading.of(n)?.takeIf { it.aya > 1 }) }
}

/** The round button that listens or pauses: the bar's and the full player's. */
@Composable
private fun PlayButton(playing: Boolean, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    val description = stringResource(if (playing) R.string.player_pause else R.string.player_listen)
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(P.primary)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (playing) Ic.Pause else Ic.Play, contentDescription = null, tint = P.onPrimary, modifier = Modifier.size(size / 2))
    }
}

/**
 * The microphone of the bar: a tap recites ([onRecite]: the passage open, or anywhere in the
 * Qur'an, the app finding what is said and following it there); the arrow beside it opens how
 * ([reciteSheet]: from memory, the text hidden, or reading, the text shown).
 */
@Composable
private fun ReciteButton(onRecite: () -> Unit, modifier: Modifier = Modifier) {
    val sheets = LocalSheets.current
    val context = LocalContext.current
    Row(
        modifier
            .height(36.dp)
            .clip(CircleShape)
            .border(1.dp, P.outline, CircleShape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.fillMaxHeight().clickable(onClickLabel = stringResource(R.string.recite_anywhere_hint), onClick = onRecite).padding(start = 10.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Ic.Mic, contentDescription = null, tint = P.accent, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(5.dp))
            Text(stringResource(R.string.recite), style = Type.button.copy(fontSize = 14.sp), color = P.text, maxLines = 1)
        }
        Box(Modifier.width(1.dp).height(18.dp).background(P.outline))
        val how = stringResource(R.string.recite_how)
        Box(
            Modifier.fillMaxHeight().clickable { sheets(reciteSheet(context, onRecite)) }.semantics { contentDescription = how }.padding(start = 5.dp, end = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Ic.ChevronDown, contentDescription = null, tint = P.textDim, modifier = Modifier.size(18.dp))
        }
    }
}

/** How to recite: from memory (the text hidden, each word showing as it is said) or reading (the text shown, followed by the voice). Choosing one starts. */
fun reciteSheet(context: android.content.Context, onRecite: () -> Unit) = SheetSpec(
    title = context.getString(R.string.recite),
    subtitle = context.getString(R.string.recite_anywhere_hint),
    content = {
        val settings by Graph.settings.state.collectAsStateWithLifecycle()
        val close = LocalSheetClose.current
        @Composable
        fun way(shown: Boolean, title: Int, body: Int, icon: androidx.compose.ui.graphics.vector.ImageVector) {
            val chosen = settings.reciteShowText == shown
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (chosen) P.accent.copy(alpha = if (P.isDark) 0.14f else 0.12f) else P.surfaceHigh)
                    .border(1.dp, if (chosen) P.accent.copy(alpha = 0.7f) else P.outline, RoundedCornerShape(16.dp))
                    .clickable {
                        Graph.settings.update { it.copy(reciteShowText = shown) }
                        close()
                        onRecite()
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = null, tint = if (chosen) P.accent else P.textDim, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(title), style = Type.title, color = P.text)
                    Spacer(Modifier.height(2.dp))
                    Text(stringResource(body), style = Type.label, color = P.textDim)
                }
                Spacer(Modifier.width(8.dp))
                Icon(Ic.Mic, contentDescription = null, tint = if (chosen) P.accent else P.textFaint, modifier = Modifier.size(18.dp))
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 12.dp)) {
            way(false, R.string.recite_way_memory, R.string.recite_way_memory_body, Ic.Hidden)
            way(true, R.string.recite_way_reading, R.string.recite_way_reading_body, Ic.Visible)
        }
    },
)

/**
 * The one bar the app listens and recites from, above the tabs: there is no other player on a
 * screen. On a tab it is what the player holds (a tap opens it). With a passage or a sūra open
 * ([open]) it is that text's: listen to it, and the full player behind it ([playerSheet]: the
 * transport, the reciter, the repeats). Something else heard while that text is open stays in
 * the bar until the text is left, so what sounds can always be paused. And everywhere, the
 * microphone ([onRecite], [ReciteButton]): with nothing to listen to, the bar is that alone.
 */
@Composable
fun PlayerDock(open: String?, onOpen: () -> Unit, onRecite: () -> Unit, modifier: Modifier = Modifier) {
    val sheets = LocalSheets.current
    val np by Graph.nowPlaying.collectAsStateWithLifecycle()
    val ui by Graph.player.state.collectAsStateWithLifecycle()
    val settings by Graph.settings.state.collectAsStateWithLifecycle()
    val ranges by Graph.ranges.ranges.collectAsStateWithLifecycle()
    val place by Graph.reading.place.collectAsStateWithLifecycle()
    val held = np
    val own = open != null && held != null && held.plays(open)
    // Another text than the open one, heard while it is open. (An āya of the open text left by a
    // lesson is neither: the bar offers the text.)
    val foreign = open != null && held != null && held.fadilaId != open
    var heard by remember(open) { mutableStateOf<PlaySession?>(null) }
    val other = foreign && (ui.playWhenReady || heard === held?.session)
    LaunchedEffect(foreign, held?.session, ui.playWhenReady) {
        if (foreign && ui.playWhenReady) heard = held?.session
    }
    val loaded = held != null && (open == null || own || other)
    val id = if (loaded) held!!.fadilaId else open
    val of = id?.let { listening(it) }
    val title = if (loaded) held!!.title else of?.title
    if (id == null || title == null) {
        // Nothing to listen to here: the microphone, and what it does.
        Row(
            modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(P.surfaceHigh)
                .padding(start = 18.dp, end = 10.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.recite_anywhere_hint), style = Type.label, color = P.textDim, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            ReciteButton(onRecite)
        }
        return
    }
    val playing = loaded && ui.playWhenReady
    // The open text's own bar: it also recites, and a tap opens the full player (elsewhere, what plays).
    val here = open != null && !other
    val full = { sheets(playerSheet(id, title, strict = open == id)) }

    val status = when {
        loaded -> listOf(itemLabel(held!!.item), held.reciter.short)
        else -> {
            val reciter = if (of?.sura != null) remember(settings.quranChoice, of.ayat) { Graph.quranReciter(of.ayat, settings) } else settings.reciterInfo
            val resume = place?.takeIf { it.sura == of?.sura && it.aya > 1 }
            val range = of?.fadila?.let { f -> remember(ranges, f) { Graph.ranges.get(f.id, f.size) }.takeIf { it.from != 0 || it.to != f.ayat.lastIndex }?.let { rangeLabel(f, it) } }
            listOf(resume?.let { stringResource(R.string.player_from_aya, it.aya) } ?: range ?: stringResource(R.string.player_listen), reciter.short)
        }
    }.filter { it.isNotBlank() }.joinToString("  ·  ")

    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(P.surfaceHigh),
    ) {
        Row(Modifier.padding(start = 8.dp, end = 6.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            PlayButton(playing, 44.dp) {
                if (loaded) Graph.player.togglePlay() else of?.let(::start)
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClickLabel = stringResource(if (here) R.string.player_open else R.string.player_show)) { if (here) full() else onOpen() }
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(title, style = Type.name.copy(fontSize = 16.sp), color = P.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(status, style = Type.label, color = P.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (loaded) IconBtn(Ic.Next, { Graph.player.nextAyah() }, tint = P.textDim, size = 40.dp, iconSize = 24.dp, contentDescription = stringResource(R.string.player_next))
            // Reciting aloud, the microphone following: the passage open, or anywhere in the Qur'an.
            ReciteButton(onRecite, Modifier.padding(horizontal = 2.dp))
            IconBtn(Ic.Expand, full, tint = P.textDim, size = 40.dp, iconSize = 24.dp, contentDescription = stringResource(R.string.player_open), modifier = Modifier.testTag("player-open"))
        }
        if (loaded) DockProgress(ui.durationMs) else Spacer(Modifier.height(12.dp))
    }
}

/** The āya's progress under the bar (on its own, as [PositionBar]: it moves ten times a second). */
@Composable
private fun DockProgress(durationMs: Long) {
    val progress by Graph.player.progress.collectAsStateWithLifecycle()
    SeekBar(
        progress = if (durationMs > 0) progress.positionMs.toFloat() / durationMs else 0f,
        modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 2.dp),
        height = 10.dp,
        thickness = 3.dp,
        showHead = false,
    )
}

/**
 * The full player, behind the bar: where the listening is, the transport, and how it is listened
 * to. [strict]: opened from the text's own screen, where an āya of it left by a lesson isn't the
 * text being listened to (Play then starts the text).
 */
fun playerSheet(id: String, title: String, strict: Boolean): SheetSpec =
    SheetSpec(title = title, content = { FullPlayer(id, title, strict) })

@Composable
private fun FullPlayer(id: String, title: String, strict: Boolean) {
    val sheets = LocalSheets.current
    val context = LocalContext.current
    val np by Graph.nowPlaying.collectAsStateWithLifecycle()
    val ui by Graph.player.state.collectAsStateWithLifecycle()
    val settings by Graph.settings.state.collectAsStateWithLifecycle()
    val ranges by Graph.ranges.ranges.collectAsStateWithLifecycle()
    val place by Graph.reading.place.collectAsStateWithLifecycle()
    val of = listening(id)
    val f = of?.fadila
    val sura = of?.sura
    val held = np
    val loaded = held != null && held.fadilaId == id && (!strict || held.session.tag == null)
    val playing = loaded && ui.playWhenReady
    val range = remember(ranges, f) { f?.let { Graph.ranges.get(it.id, it.size) } }
    val resumeAt = place?.takeIf { it.sura == sura && it.aya > 1 }
    // A sūra: the reciter chosen for the section, or the one who takes over where that one has no āyāt.
    val choice = settings.quranChoice
    val reciter = if (of != null && sura != null) remember(choice, of.ayat) { Graph.quranReciter(of.ayat, settings) } else settings.reciterInfo
    // A setting's own sheet comes back here once chosen.
    val back = { playerSheet(id, title, strict) }

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    loaded -> itemLabel(held?.item)
                    f != null && range != null -> rangeLabel(f, range)
                    else -> resumeAt?.let { stringResource(R.string.player_from_aya, it.aya) } ?: stringResource(R.string.chip_all)
                },
                style = Type.clock,
                color = P.text,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (f != null) OfflineChip(f)
            else if (of != null && sura != null) {
                OfflineChip(reciter, remember(of.ayat) { if (sura != 1 && sura != 9) of.ayat + app.hfd.core.playback.EveryAyah.BASMALA else of.ayat }, optional = true)
            }
        }
        if (loaded) PositionBar(ui.durationMs) else Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TransportButton(Ic.Previous, stringResource(R.string.player_previous), enabled = loaded) { Graph.player.previousAyah() }
            Spacer(Modifier.width(22.dp))
            PlayButton(playing, 60.dp) { if (loaded) Graph.player.togglePlay() else of?.let(::start) }
            Spacer(Modifier.width(22.dp))
            TransportButton(Ic.Next, stringResource(R.string.player_next), enabled = loaded) { Graph.player.nextAyah() }
        }
        Spacer(Modifier.height(8.dp))
        // A sūra plays through, then the next: the repeats and the pauses are the passages'.
        if (f != null) {
            val each = AppSettings.REPEAT_EACH
            Setting(stringResource(R.string.repeat_each)) {
                Segmented(each.map { "×" + timesLabel(it) }, each.indexOf(settings.repeatEach).coerceAtLeast(0), { i -> Graph.settings.update { it.copy(repeatEach = each[i]) } })
            }
            val whole = AppSettings.REPEAT_RANGE
            Setting(stringResource(R.string.repeat_range)) {
                Segmented(whole.map { "×" + timesLabel(it) }, whole.indexOf(settings.repeatRange).coerceAtLeast(0), { i -> Graph.settings.update { it.copy(repeatRange = whole[i]) } })
            }
            Setting(stringResource(R.string.gap)) {
                Segmented(
                    GapMode.entries.map { if (it == GapMode.NONE) stringResource(R.string.gap_none) else gapLabel(it) },
                    settings.gap.ordinal,
                    { i -> Graph.settings.update { it.copy(gap = GapMode.entries[i]) } },
                )
            }
        }
        val speeds = AppSettings.SPEEDS
        Setting(stringResource(R.string.speed)) {
            Segmented(speeds.map { speedLabel(it) }, speeds.indexOf(settings.speed).let { if (it < 0) speeds.indexOf(1f) else it }, { i -> Graph.settings.update { it.copy(speed = speeds[i]) } })
        }
        Spacer(Modifier.height(4.dp))
        if (sura != null) {
            Choice(
                stringResource(R.string.reciter),
                if (reciter.id == choice.id) reciter.name else stringResource(R.string.quran_reciter_instead, reciter.short, choice.short),
            ) { sheets(quranReciterSheet(context, choice.id).thenBack(back)) }
        } else {
            Choice(stringResource(R.string.reciter), settings.reciterInfo.name) { sheets(reciterSheet(context, settings.reciter).thenBack(back)) }
        }
        if (f != null && range != null) {
            Choice(stringResource(R.string.range), rangeLabel(f, range), lit = range.from != 0 || range.to != f.ayat.lastIndex) { sheets(rangeSheet(context, f, title)) }
        }
        val sleep = if (loaded) held?.sleep else null
        Choice(stringResource(R.string.sleep), sleepLabel(sleep).ifEmpty { stringResource(R.string.sleep_off) }, lit = sleep != null) {
            sheets((if (sura != null) sleepSheet(context, context.getString(R.string.sleep_end_sura)) else sleepSheet(context)).thenBack(back))
        }
    }
}

/** A sheet whose choices come back to [back] (the full player) once made, where they closed everything. */
private fun SheetSpec.thenBack(back: () -> SheetSpec): SheetSpec =
    copy(actions = actions.map { a -> if (a.next != null) a else a.copy(next = { a.onClick(); back() }) })

/** A listening setting with its choices under its name. */
@Composable
private fun Setting(label: String, control: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, style = Type.label, color = P.textDim)
        Spacer(Modifier.height(6.dp))
        control()
    }
}

/** A listening setting chosen in its own sheet: its name, what is chosen ([lit]: other than the usual). */
@Composable
private fun Choice(label: String, value: String, lit: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = Type.label, color = P.textDim)
        Spacer(Modifier.width(12.dp))
        Text(value, style = Type.title, color = if (lit) P.accent else P.text, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(6.dp))
        Text("›", style = Type.title, color = P.textFaint)
    }
}

/**
 * Where the āya playing is, to drag. On its own: the position changes ten times a second, and
 * read in a panel it had the whole panel drawn again each time.
 */
@Composable
private fun PositionBar(durationMs: Long) {
    val progress by Graph.player.progress.collectAsStateWithLifecycle()
    val fraction = if (durationMs > 0) progress.positionMs.toFloat() / durationMs else 0f
    SeekBar(
        progress = fraction,
        onSeek = { p -> if (durationMs > 0) Graph.player.seekTo((p * durationMs).toLong()) },
        modifier = Modifier.padding(horizontal = 14.dp),
        height = 22.dp,
    )
}

@Composable
private fun sleepLabel(sleep: Sleep?): String = when (sleep) {
    null -> ""
    Sleep.EndOfFadila -> stringResource(R.string.sleep_chip_end)
    is Sleep.At -> stringResource(R.string.sleep_minutes, ((sleep.endsAtMs - System.currentTimeMillis()) / 60_000 + 1).coerceAtLeast(1).toInt())
}

@Composable
private fun TransportButton(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) P.text else P.textFaint, modifier = Modifier.size(28.dp))
    }
}

// ---------------------------------------------------------------------- sheets

fun reciterSheet(context: android.content.Context, current: String) = SheetSpec(
    title = context.getString(R.string.reciter),
    actions = Reciters.ALL.map { r ->
        SheetAction(
            label = "${r.name} · ${r.style}",
            icon = if (r.id == current) Ic.Check else null,
        ) { Graph.settings.update { it.copy(reciter = r.id) } }
    },
)

/**
 * The Qur'an section's reciter. A whole-sūra recitation says how many sūras it is cut into āyāt
 * for: it recites those, and [QuranListening.FALLBACK] the others.
 */
fun quranReciterSheet(context: android.content.Context, current: String): SheetSpec {
    val sizes = Graph.content.content.value?.suras?.map { it.ayas }.orEmpty()
    // How many sūras a whole-sūra recitation is cut into āyāt for: said only while some are missing.
    val cut = Reciters.ALL.filter { it.timings != null }.associateWith { r ->
        val t = Graph.timings.of(r)
        sizes.indices.count { i -> QuranListening.has(r, t, QuranListening.ayat(i + 1, sizes[i])) }
    }.filterValues { it < sizes.size }
    return SheetSpec(
        title = context.getString(R.string.reciter),
        subtitle = if (cut.isEmpty()) context.getString(R.string.quran_reciter_body_all)
        else context.getString(R.string.quran_reciter_body, QuranListening.FALLBACK.short),
        actions = Reciters.ALL.map { r ->
            val partial = cut[r]?.let { " · " + context.getString(R.string.quran_reciter_suras, it, sizes.size) }.orEmpty()
            SheetAction(
                label = "${r.name} · ${r.style}$partial",
                icon = if (r.id == current) Ic.Check else null,
            ) { Graph.settings.update { it.copy(quranReciter = r.id) } }
        },
    )
}

/** The timer; [end]: what "at the end" stops after (the faḍīla, or the sūra in the Qur'an section). */
fun sleepSheet(context: android.content.Context, end: String = context.getString(R.string.sleep_end)) = SheetSpec(
    title = context.getString(R.string.sleep),
    actions = listOf(
        SheetAction(end) { Graph.player.sleepAtEnd() },
        SheetAction(context.getString(R.string.sleep_minutes, 15)) { Graph.player.sleepAfter(15) },
        SheetAction(context.getString(R.string.sleep_minutes, 30)) { Graph.player.sleepAfter(30) },
        SheetAction(context.getString(R.string.sleep_minutes, 60)) { Graph.player.sleepAfter(60) },
        SheetAction(context.getString(R.string.sleep_off), Ic.Close) { Graph.player.cancelSleep() },
    ),
)

fun rangeSheet(context: android.content.Context, f: Fadila, title: String) = SheetSpec(
    title = context.getString(R.string.range),
    subtitle = context.getString(R.string.range_body),
    content = {
        val ranges by Graph.ranges.ranges.collectAsStateWithLifecycle()
        val r = remember(ranges) { Graph.ranges.get(f.id, f.size) }
        val ayat = f.ayat
        Column {
            RangeStepper(context.getString(R.string.range_from), ayat[r.from], r.from > 0, r.from < r.to,
                onMinus = { Graph.ranges.set(f.id, r.copy(from = r.from - 1)) },
                onPlus = { Graph.ranges.set(f.id, r.copy(from = r.from + 1)) })
            Spacer(Modifier.height(8.dp))
            RangeStepper(context.getString(R.string.range_to), ayat[r.to], r.to > r.from, r.to < ayat.lastIndex,
                onMinus = { Graph.ranges.set(f.id, r.copy(to = r.to - 1)) },
                onPlus = { Graph.ranges.set(f.id, r.copy(to = r.to + 1)) })
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(context.getString(R.string.range_whole), { Graph.ranges.set(f.id, null) })
                PillButton(context.getString(R.string.player_listen), {
                    Graph.player.play(sessionFor(f, title, Graph.ranges.get(f.id, f.size)))
                }, style = PillStyle.Accent)
            }
        }
    },
)

@Composable
private fun RangeStepper(label: String, ref: AyahRef, canMinus: Boolean, canPlus: Boolean, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Type.label, color = P.textDim, modifier = Modifier.width(56.dp))
        StepCircle("−", canMinus, onMinus)
        Text(ref.toString(), style = Type.clock, color = P.text, modifier = Modifier.padding(horizontal = 14.dp).width(84.dp), maxLines = 1)
        StepCircle("+", canPlus, onPlus)
    }
}

@Composable
private fun StepCircle(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(38.dp).clip(CircleShape).border(1.dp, P.outline, CircleShape).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = Type.title, color = if (enabled) P.text else P.textFaint)
    }
}
