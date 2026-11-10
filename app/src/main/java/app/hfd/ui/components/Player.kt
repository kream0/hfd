package app.hfd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

/** A listening setting under the player; [active]: other than its default, lit. */
@Composable
private fun SettingChip(text: String, onClick: () -> Unit, active: Boolean = false, icon: ImageVector? = null) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(if (active) P.accent.copy(alpha = 0.14f) else Color.Transparent)
            .border(1.dp, if (active) P.accent.copy(alpha = 0.7f) else P.outline, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = if (active) P.accent else P.textDim, modifier = Modifier.size(15.dp))
            if (text.isNotEmpty()) Spacer(Modifier.width(5.dp))
        }
        if (text.isNotEmpty()) Text(text, style = Type.labelBold, color = if (active) P.text else P.textDim, maxLines = 1)
    }
}

/** Bottom panel of the faḍīla screen: what's playing, transport, and the listening settings. */
@Composable
fun PlayerPanel(f: Fadila, modifier: Modifier = Modifier) {
    val sheets = LocalSheets.current
    val context = LocalContext.current
    val np by Graph.nowPlaying.collectAsStateWithLifecycle()
    val ui by Graph.player.state.collectAsStateWithLifecycle()
    val settings by Graph.settings.state.collectAsStateWithLifecycle()
    val ranges by Graph.ranges.ranges.collectAsStateWithLifecycle()
    val range = remember(ranges, f) { Graph.ranges.get(f.id, f.size) }
    val title = f.title.text
    val isThis = np?.fadilaId == f.id
    val playing = isThis && ui.playWhenReady

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(P.surface)
            .padding(top = 14.dp, bottom = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (isThis) itemLabel(np?.item) else rangeLabel(f, range),
                style = Type.clock,
                color = P.text,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            OfflineChip(f)
        }
        if (isThis) {
            val progress by Graph.player.progress.collectAsStateWithLifecycle()
            val fraction = if (ui.durationMs > 0) progress.positionMs.toFloat() / ui.durationMs else 0f
            SeekBar(
                progress = fraction,
                onSeek = { p -> if (ui.durationMs > 0) Graph.player.seekTo((p * ui.durationMs).toLong()) },
                modifier = Modifier.padding(horizontal = 14.dp),
                height = 22.dp,
            )
        } else {
            Spacer(Modifier.height(8.dp))
        }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TransportButton(Ic.Previous, stringResource(R.string.player_previous), enabled = isThis) { Graph.player.previousAyah() }
            Spacer(Modifier.width(22.dp))
            Box(
                Modifier
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(P.primary)
                    .clickable {
                        if (isThis) Graph.player.togglePlay() else Graph.player.play(sessionFor(f, title, range))
                    }
                    .semantics { contentDescription = context.getString(if (playing) R.string.player_pause else R.string.player_play) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (playing) Ic.Pause else Ic.Play, contentDescription = null, tint = P.onPrimary, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.width(22.dp))
            TransportButton(Ic.Next, stringResource(R.string.player_next), enabled = isThis) { Graph.player.nextAyah() }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SettingChip(stringResource(R.string.chip_ayah, timesLabel(settings.repeatEach)), { sheets(repeatEachSheet(context)) }, settings.repeatEach != 1)
            SettingChip(stringResource(R.string.chip_range, timesLabel(settings.repeatRange)), { sheets(repeatRangeSheet(context)) }, settings.repeatRange != 1)
            SettingChip(stringResource(R.string.chip_gap, gapLabel(settings.gap)), { sheets(gapSheet(context)) }, settings.gap != GapMode.NONE)
            SettingChip(speedLabel(settings.speed), { sheets(speedSheet(context)) }, settings.speed != 1f)
            SettingChip(settings.reciterInfo.short, { sheets(reciterSheet(context, settings.reciter)) })
            SettingChip(rangeLabel(f, range), { sheets(rangeSheet(context, f, title)) }, range.from != 0 || range.to != f.ayat.lastIndex)
            val sleep = if (isThis) np?.sleep else null
            SettingChip(sleepLabel(sleep), { sheets(sleepSheet(context)) }, sleep != null, icon = Ic.Moon)
        }
    }
}

/**
 * Bottom panel of a sūra of the Qur'an section: what's playing and the transport, with the speed,
 * the section's reciter and the timer. No repeats nor pauses: a sūra plays through, then the next.
 */
@Composable
fun SuraPlayerPanel(sura: Int, ayat: List<AyahRef>, modifier: Modifier = Modifier) {
    val sheets = LocalSheets.current
    val context = LocalContext.current
    val np by Graph.nowPlaying.collectAsStateWithLifecycle()
    val ui by Graph.player.state.collectAsStateWithLifecycle()
    val settings by Graph.settings.state.collectAsStateWithLifecycle()
    val place by Graph.quranPlace.place.collectAsStateWithLifecycle()
    val isThis = np?.session?.sura == sura
    val playing = isThis && ui.playWhenReady
    // The reciter chosen for the section, or the one who takes over for a sūra that one doesn't have.
    val choice = settings.quranChoice
    val reciter = remember(choice, ayat) { Graph.quranReciter(ayat, settings) }
    // Where this sūra was left, if it is the one the section was last listened to.
    val resumeAt = place?.takeIf { it.sura == sura && it.aya > 1 }

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(P.surface)
            .padding(top = 14.dp, bottom = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (isThis) itemLabel(np?.item) else resumeAt?.toString() ?: stringResource(R.string.chip_all),
                style = Type.clock,
                color = P.text,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            OfflineChip(reciter, remember(ayat) { if (sura != 1 && sura != 9) ayat + app.hfd.core.playback.EveryAyah.BASMALA else ayat }, optional = true)
        }
        if (isThis) {
            val progress by Graph.player.progress.collectAsStateWithLifecycle()
            val fraction = if (ui.durationMs > 0) progress.positionMs.toFloat() / ui.durationMs else 0f
            SeekBar(
                progress = fraction,
                onSeek = { p -> if (ui.durationMs > 0) Graph.player.seekTo((p * ui.durationMs).toLong()) },
                modifier = Modifier.padding(horizontal = 14.dp),
                height = 22.dp,
            )
        } else {
            Spacer(Modifier.height(8.dp))
        }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TransportButton(Ic.Previous, stringResource(R.string.player_previous), enabled = isThis) { Graph.player.previousAyah() }
            Spacer(Modifier.width(22.dp))
            Box(
                Modifier
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(P.primary)
                    .clickable { if (isThis) Graph.player.togglePlay() else Graph.playSura(sura, resumeAt) }
                    .semantics { contentDescription = context.getString(if (playing) R.string.player_pause else R.string.player_play) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (playing) Ic.Pause else Ic.Play, contentDescription = null, tint = P.onPrimary, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.width(22.dp))
            TransportButton(Ic.Next, stringResource(R.string.player_next), enabled = isThis) { Graph.player.nextAyah() }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SettingChip(
                if (reciter.id == choice.id) reciter.short else stringResource(R.string.quran_reciter_instead, reciter.short, choice.short),
                { sheets(quranReciterSheet(context, choice.id)) },
            )
            SettingChip(speedLabel(settings.speed), { sheets(speedSheet(context)) }, settings.speed != 1f)
            val sleep = if (isThis) np?.sleep else null
            SettingChip(sleepLabel(sleep), { sheets(sleepSheet(context, context.getString(R.string.sleep_end_sura))) }, sleep != null, icon = Ic.Moon)
        }
    }
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

private fun optionsSheet(title: String, body: String, content: @Composable () -> Unit) =
    SheetSpec(title = title, subtitle = null, content = {
        Column {
            content()
            Spacer(Modifier.height(10.dp))
            Text(body, style = Type.body, color = P.textDim)
        }
    })

fun repeatEachSheet(context: android.content.Context) = optionsSheet(
    context.getString(R.string.repeat_each), context.getString(R.string.repeat_each_body),
) {
    val s by Graph.settings.state.collectAsStateWithLifecycle()
    val options = AppSettings.REPEAT_EACH
    Segmented(options.map { "×" + timesLabel(it) }, options.indexOf(s.repeatEach).coerceAtLeast(0), { i ->
        Graph.settings.update { it.copy(repeatEach = options[i]) }
    })
}

fun repeatRangeSheet(context: android.content.Context) = optionsSheet(
    context.getString(R.string.repeat_range), context.getString(R.string.repeat_range_body),
) {
    val s by Graph.settings.state.collectAsStateWithLifecycle()
    val options = AppSettings.REPEAT_RANGE
    Segmented(options.map { "×" + timesLabel(it) }, options.indexOf(s.repeatRange).coerceAtLeast(0), { i ->
        Graph.settings.update { it.copy(repeatRange = options[i]) }
    })
}

fun gapSheet(context: android.content.Context) = optionsSheet(
    context.getString(R.string.gap), context.getString(R.string.gap_body),
) {
    val s by Graph.settings.state.collectAsStateWithLifecycle()
    Segmented(
        GapMode.entries.map { if (it == GapMode.NONE) context.getString(R.string.gap_none) else gapLabel(it) },
        s.gap.ordinal,
        { i -> Graph.settings.update { it.copy(gap = GapMode.entries[i]) } },
    )
}

fun speedSheet(context: android.content.Context) = optionsSheet(
    context.getString(R.string.speed), "",
) {
    val s by Graph.settings.state.collectAsStateWithLifecycle()
    val options = AppSettings.SPEEDS
    Segmented(options.map { speedLabel(it) }, options.indexOf(s.speed).let { if (it < 0) options.indexOf(1f) else it }, { i ->
        Graph.settings.update { it.copy(speed = options[i]) }
    })
}

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
    return SheetSpec(
        title = context.getString(R.string.reciter),
        subtitle = context.getString(R.string.quran_reciter_body, QuranListening.FALLBACK.short),
        actions = Reciters.ALL.map { r ->
            val partial = r.timings?.let {
                val t = Graph.timings.of(r)
                val suras = sizes.indices.count { i -> QuranListening.has(r, t, QuranListening.ayat(i + 1, sizes[i])) }
                " · " + context.getString(R.string.quran_reciter_suras, suras, sizes.size)
            }.orEmpty()
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
