package app.hfd.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hfd.Graph
import app.hfd.R
import app.hfd.core.fadail.FadailGrouping
import app.hfd.core.progress.LearnStep
import app.hfd.core.progress.Stats
import app.hfd.data.AppMode
import app.hfd.ui.AppViewModel
import app.hfd.ui.components.DotLoader
import app.hfd.ui.components.DotRing
import app.hfd.ui.components.FadilaRow
import app.hfd.ui.components.PillButton
import app.hfd.ui.components.PillStyle
import app.hfd.ui.components.ScreenHeader
import app.hfd.ui.components.SectionLabel
import app.hfd.ui.label
import app.hfd.ui.text
import app.hfd.ui.theme.P
import app.hfd.ui.theme.Type
import java.time.LocalDateTime

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HomeScreen(app: AppViewModel) {
    val content by Graph.content.content.collectAsStateWithLifecycle()
    val settings by Graph.settings.state.collectAsStateWithLifecycle()
    val progress by Graph.progress.state.collectAsStateWithLifecycle()
    val session by Graph.sessions.session.collectAsStateWithLifecycle()
    val np by Graph.nowPlaying.collectAsStateWithLifecycle()
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        ScreenHeader(stringResource(R.string.app_name).uppercase())
        val c = content
        if (c == null) {
            Box(Modifier.fillMaxSize().padding(top = 80.dp), contentAlignment = Alignment.Center) { DotLoader() }
            return@Column
        }
        val now = System.currentTimeMillis()
        val zone = Graph.progress.zone
        val today = Stats.today(now, zone)
        val goalMs = settings.dailyGoalMin * 60_000L
        val todayMs = progress.days[today]?.practiceMs ?: 0
        val streak = remember(progress, goalMs, today) { Stats.streak(progress.days, goalMs, today) }
        val due = remember(progress) { Stats.due(progress, now, zone) }

        // Today: goal ring, streak, and what's due.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(24.dp)).background(P.surface).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val fraction = (todayMs.toFloat() / goalMs).coerceIn(0f, 1f)
            DotRing(fraction, Modifier.size(52.dp), color = if (fraction >= 1f) P.saveGreen else P.text, dots = 24)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.home_goal).uppercase(), style = Type.label, color = P.textDim)
                Text(stringResource(R.string.home_goal_value, (todayMs / 60_000).toInt(), settings.dailyGoalMin), style = Type.clock, color = P.text)
                Text(stringResource(R.string.home_streak, streak.current).uppercase(), style = Type.label, color = if (streak.todayDone) P.accent else P.textDim)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(24.dp)).background(P.surface).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.home_due).uppercase(), style = Type.label, color = P.textDim)
                Text(pluralStringResource(R.plurals.home_due_count, due.size, due.size), style = Type.title, color = P.text)
            }
            PillButton(stringResource(R.string.review), { app.review() }, style = if (due.isNotEmpty()) PillStyle.Accent else PillStyle.Outline, enabled = due.isNotEmpty())
        }

        // Continue where you left off.
        val s = session
        val cont: Pair<String, () -> Unit>? = when {
            s == null -> null
            s.mode == AppMode.LEARN && s.learn != null && s.learn.step != LearnStep.DONE -> c.fadila(s.learn.fadilaId)?.let { f ->
                stringResource(R.string.home_continue_learn, f.title.text, f.ayat[s.learn.index].toString()) to { app.learn(f) }
            }
            s.mode == AppMode.TEST && s.test != null -> c.fadila(s.test.fadilaId)?.takeIf { s.test.index < it.size }?.let { f ->
                stringResource(R.string.home_continue_test, f.title.text) to { app.test(f) }
            }
            else -> null
        } ?: np?.session?.let { ps -> c.fadila(ps.fadilaId)?.let { f -> stringResource(R.string.home_continue_listen, f.title.text) to { app.openFadila(f.id) } } }
        if (cont != null) {
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(24.dp)).background(P.surface).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.home_continue).uppercase(), style = Type.label, color = P.textDim)
                    Text(cont.first, style = Type.title, color = P.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                PillButton(stringResource(R.string.home_continue), cont.second, style = PillStyle.Filled)
            }
        }

        val visible = remember(c) { c.fadail.filter { it.visible(showWeak = false) } }
        fun progressOf(f: app.hfd.core.fadail.Fadila) = Stats.fadila(progress, f, Graph.progress.fsrs, now).fraction

        val tests = visible.filter { Stats.testSuggested(progress, it, now) }
        if (tests.isNotEmpty()) {
            SectionLabel(stringResource(R.string.home_test_suggested), Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 4.dp))
            tests.forEach { f -> FadilaRow(f, progressOf(f), onClick = { app.test(f) }) }
        }

        val occasions = remember { FadailGrouping.now(LocalDateTime.now()) }
        if (occasions.isNotEmpty()) {
            val suggested = visible.filter { f -> f.occasions.any { it in occasions } }
            if (suggested.isNotEmpty()) {
                val label = occasions.map { stringResource(it.label) }.joinToString(" · ")
                SectionLabel(stringResource(R.string.home_now, label), Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 4.dp))
                suggested.forEach { FadilaRow(it, progressOf(it), onClick = { app.openFadila(it.id) }) }
            }
        }
        val daily = visible.filter { "daily" in it.tags && it.occasions.none { o -> o in occasions } }
        if (daily.isNotEmpty()) {
            SectionLabel(stringResource(R.string.home_daily), Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 4.dp))
            daily.forEach { FadilaRow(it, progressOf(it), onClick = { app.openFadila(it.id) }) }
        }
        Box(Modifier.padding(20.dp)) { PillButton(stringResource(R.string.home_all), { app.selectTab(app.hfd.ui.Tab.FADAIL) }) }
    }
}
