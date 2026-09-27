package app.hfd.ui.screens

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hfd.Graph
import app.hfd.R
import app.hfd.core.progress.DayStats
import app.hfd.core.progress.Stats
import app.hfd.ui.components.DotLoader
import app.hfd.ui.components.DotRing
import app.hfd.ui.components.ScreenHeader
import app.hfd.ui.components.SectionLabel
import app.hfd.ui.components.formatInterval
import app.hfd.ui.text
import app.hfd.ui.theme.P
import app.hfd.ui.theme.Type
import app.hfd.ui.uiLanguage
import java.time.LocalDate
import java.util.Locale

fun formatDuration(ms: Long): String {
    val minutes = ms / 60_000
    return if (minutes < 60) "${minutes}m" else String.format(Locale.US, "%dh%02d", minutes / 60, minutes % 60)
}

@Composable
fun StatsScreen(onOpen: (String) -> Unit) {
    val content by Graph.content.content.collectAsStateWithLifecycle()
    val progress by Graph.progress.state.collectAsStateWithLifecycle()
    val settings by Graph.settings.state.collectAsStateWithLifecycle()
    val now = System.currentTimeMillis()
    val zone = Graph.progress.zone
    val today = Stats.today(now, zone)
    val goalMs = settings.dailyGoalMin * 60_000L
    val streak = remember(progress, goalMs, today) { Stats.streak(progress.days, goalMs, today) }
    val todayMs = progress.days[today]?.practiceMs ?: 0

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        ScreenHeader(stringResource(R.string.stats_title).uppercase())

        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigNumber(streak.current.toString(), stringResource(R.string.stats_streak), Modifier.weight(1f))
            BigNumber(streak.best.toString(), stringResource(R.string.stats_best), Modifier.weight(1f))
            BigNumber(Stats.memorisedCount(progress).toString(), stringResource(R.string.stats_memorised), Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigNumber(formatDuration(Stats.totalListenMs(progress)), stringResource(R.string.stats_listening), Modifier.weight(1f))
            BigNumber(progress.days.values.sumOf { it.ratings }.toString(), stringResource(R.string.stats_reviews), Modifier.weight(1f))
            BigNumber(progress.days.values.sumOf { it.listens }.toString(), stringResource(R.string.stats_listens), Modifier.weight(1f))
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel(stringResource(R.string.stats_goal), Modifier.padding(horizontal = 20.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            val fraction = (todayMs.toFloat() / goalMs).coerceIn(0f, 1f)
            DotRing(fraction, Modifier.size(56.dp), color = if (fraction >= 1f) P.saveGreen else P.text, dots = 24)
            Spacer(Modifier.width(16.dp))
            Text(
                stringResource(R.string.home_goal_value, (todayMs / 60_000).toInt(), settings.dailyGoalMin),
                style = Type.displaySmall,
                color = P.text,
            )
        }

        Spacer(Modifier.height(12.dp))
        SectionLabel(stringResource(R.string.stats_calendar), Modifier.padding(horizontal = 20.dp))
        Heatmap(progress.days, today, goalMs, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp))

        Spacer(Modifier.height(8.dp))
        SectionLabel(stringResource(R.string.stats_fadail), Modifier.padding(horizontal = 20.dp))
        val c = content
        if (c == null) {
            DotLoader(Modifier.padding(20.dp))
        } else {
            val lang = uiLanguage
            c.fadail.filter { it.visible(settings.showWeak) }.forEach { f ->
                val p = Stats.fadila(progress, f, Graph.progress.fsrs, now)
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(f.id) }.padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DotRing(p.fraction, Modifier.size(22.dp), color = P.saveColor(p.fraction))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.title.text, style = Type.title, color = P.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val due = p.nextDue?.let { d ->
                            " · " + stringResource(R.string.progress_next_due, if (d <= now) stringResource(R.string.due_now) else stringResource(R.string.due_in, formatInterval(d - now, lang)))
                        }.orEmpty()
                        Text(
                            (stringResource(R.string.progress_memorised, p.memorised, p.total) + due).uppercase(),
                            style = Type.label,
                            color = P.textDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BigNumber(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, style = Type.display, color = P.text, maxLines = 1)
        Text(label.uppercase(), style = Type.label, color = P.textDim, maxLines = 2)
    }
}

/**
 * Twenty weeks of practice as a dot matrix (columns = weeks, rows = Monday…Sunday): dim dots
 * for no practice, brighter as the day's practice grows, red once the goal was met.
 */
@Composable
private fun Heatmap(days: Map<Long, DayStats>, today: Long, goalMs: Long, modifier: Modifier = Modifier) {
    val weeks = 20
    val todayDate = LocalDate.ofEpochDay(today)
    val lastMonday = todayDate.minusDays((todayDate.dayOfWeek.value - 1).toLong()).toEpochDay()
    val first = lastMonday - (weeks - 1) * 7
    val off = P.dotOff
    val low = P.textFaint
    val mid = P.textDim
    val full = P.accent
    val future = P.background
    Canvas(modifier.height(120.dp)) {
        val cell = minOf(size.width / weeks, size.height / 7)
        val r = cell * 0.36f
        for (w in 0 until weeks) for (d in 0 until 7) {
            val day = first + w * 7 + d
            val ms = days[day]?.practiceMs ?: 0
            val color = when {
                day > today -> future
                ms <= 0 -> off
                ms >= goalMs -> full
                ms >= goalMs / 2 -> mid
                else -> low
            }
            val radius = if (day == today) r * 1.25f else r
            drawCircle(color, radius, Offset(w * cell + cell / 2, d * cell + cell / 2))
        }
    }
}
