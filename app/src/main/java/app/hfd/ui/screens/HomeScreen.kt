package app.hfd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.unit.sp
import app.hfd.ui.components.Star
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hfd.Graph
import app.hfd.R
import app.hfd.core.fadail.Fadila
import app.hfd.core.progress.LearnOrder
import app.hfd.core.progress.LearnStep
import app.hfd.core.progress.LearningPath
import app.hfd.core.progress.Stats
import app.hfd.core.quran.AyahRef
import app.hfd.core.recite.Arabic
import app.hfd.data.AppMode
import app.hfd.ui.AppViewModel
import app.hfd.ui.Tab
import app.hfd.ui.components.Loader
import app.hfd.ui.components.ProgressRing
import app.hfd.ui.components.FadilaRow
import app.hfd.ui.components.Ic
import app.hfd.ui.components.LocalSheets
import app.hfd.ui.components.PillButton
import app.hfd.ui.components.PillStyle
import app.hfd.ui.components.ScreenHeader
import app.hfd.ui.components.card
import app.hfd.ui.components.SectionLabel
import app.hfd.ui.components.SheetAction
import app.hfd.ui.components.SheetSpec
import app.hfd.ui.text
import app.hfd.ui.theme.P
import app.hfd.ui.theme.Type

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HomeScreen(app: AppViewModel) {
    val content by Graph.content.content.collectAsStateWithLifecycle()
    val settings by Graph.settings.state.collectAsStateWithLifecycle()
    val progress by Graph.progress.state.collectAsStateWithLifecycle()
    val session by Graph.sessions.session.collectAsStateWithLifecycle()
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        ScreenHeader(stringResource(R.string.app_name))
        val c = content
        if (c == null) {
            Box(Modifier.fillMaxSize().padding(top = 80.dp), contentAlignment = Alignment.Center) { Loader() }
            return@Column
        }
        val now = System.currentTimeMillis()
        val zone = Graph.progress.zone
        val today = Stats.today(now, zone)
        val goalMs = settings.dailyGoalMin * 60_000L
        val todayMs = progress.days[today]?.practiceMs ?: 0
        val streak = remember(progress, goalMs, today) { Stats.streak(progress.days, goalMs, today) }
        // (Of the passages shown: a hidden one's āyāt aren't asked for.)
        val due = remember(progress, c.shownAyat) { Stats.due(progress, now, zone, c.shownAyat) }

        // Today, in one card: the goal's ring, the streak, the week at a glance, and what is due.
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).card().padding(18.dp)) {
            val fraction = (todayMs.toFloat() / goalMs).coerceIn(0f, 1f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.home_goal).uppercase(), style = Type.overline, color = P.textDim, modifier = Modifier.weight(1f))
                Text(remember(today) { dateLine(today) }, style = Type.label, color = P.textDim)
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(contentAlignment = Alignment.Center) {
                    ProgressRing(fraction, Modifier.size(68.dp), color = if (fraction >= 1f) P.saveGreen else P.accent)
                    if (fraction >= 1f) Icon(Ic.Check, contentDescription = null, tint = P.saveGreen, modifier = Modifier.size(26.dp))
                    else Star(Modifier.size(12.dp), color = P.accent.copy(alpha = if (fraction > 0f) 1f else 0.5f))
                }
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.home_goal_value, (todayMs / 60_000).toInt(), settings.dailyGoalMin), style = Type.titleLarge, color = P.text)
                    Text(
                        if (streak.current == 0) stringResource(R.string.home_streak_none) else pluralStringResource(R.plurals.home_streak_days, streak.current, streak.current),
                        style = Type.label,
                        color = if (streak.todayDone) P.accent else P.textDim,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            WeekStrip(progress.days, today, goalMs)
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(P.outline.copy(alpha = 0.7f)))
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.home_due).uppercase(), style = Type.overline, color = P.textDim)
                    Spacer(Modifier.height(2.dp))
                    Text(pluralStringResource(R.plurals.home_due_count, due.size, due.size), style = Type.title, color = P.text)
                }
                PillButton(stringResource(R.string.review), { app.review() }, style = if (due.isNotEmpty()) PillStyle.Accent else PillStyle.Outline, enabled = due.isNotEmpty())
            }
        }

        // A lesson or a test left unfinished: to take up again.
        val s = session
        val cont: Pair<String, () -> Unit>? = when {
            s == null -> null
            s.mode == AppMode.LEARN && s.learn != null && s.learn.step != LearnStep.DONE -> c.fadila(s.learn.fadilaId)?.let { f ->
                stringResource(R.string.home_continue_learn, f.title.text, f.ayat[s.learn.index].toString()) to { app.learn(f) }
            }
            s.mode == AppMode.TEST && s.test != null -> c.fadila(s.test.fadilaId)?.takeIf { s.test.index < it.size }?.let { f ->
                stringResource(R.string.home_continue_test, f.title.text) to { app.test(f) }
            }
            // (What was being listened to is the bar's, just under this screen: not offered twice.)
            else -> null
        }
        if (cont != null) {
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).card().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.home_continue).uppercase(), style = Type.overline, color = P.textDim)
                    Text(cont.first, style = Type.title, color = P.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(10.dp))
                PillButton(stringResource(R.string.home_continue), cont.second, style = PillStyle.Outline)
            }
        }

        val all = c.fadail
        fun progressOf(f: Fadila) = Stats.fadila(progress, f, Graph.progress.fsrs, now).fraction

        // One suggestion per text (the closing repeats al-Fātiḥa and the opening of al-Baqara).
        val tests = all.filter { Stats.testSuggested(progress, it, now) }.distinctBy { f -> f.ayat.toSet() }
        if (tests.isNotEmpty()) {
            SectionLabel(stringResource(R.string.home_test_suggested), Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 4.dp))
            tests.forEach { f -> FadilaRow(f, progressOf(f), onClick = { app.test(f) }) }
        }

        // Learning first: carry on with the passages begun, then the next ones, in the chosen order.
        // (Not the one the Continue card already resumes.)
        val resumed = if (s != null && s.mode == AppMode.LEARN && s.learn != null && s.learn.step != LearnStep.DONE) s.learn.fadilaId else null
        val words = remember(c) { { ref: AyahRef -> Arabic.words(c.text(ref)).size } }
        val order = settings.learnOrder
        val learning = remember(progress, all, resumed, order) { LearningPath.inProgress(all, progress, words, order).filter { it.id != resumed } }
        if (learning.isNotEmpty()) {
            SectionLabel(stringResource(R.string.home_learning), Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 4.dp))
            learning.forEach { f -> FadilaRow(f, progressOf(f), onClick = { app.learn(f) }) }
        }
        val next = remember(progress, all, order) { LearningPath.next(all, progress, words, order) }
        if (next.isNotEmpty()) {
            // (In the order chosen in the settings.)
            SectionLabel(stringResource(R.string.home_next_plain), Modifier.padding(start = 20.dp, end = 12.dp, top = 12.dp))
            next.forEach { f -> FadilaRow(f, progressOf(f), onClick = { app.openFadila(f.id) }) }
        }
        // (Room under the last row for the bar's button, which floats over this screen's corner.)
        Spacer(Modifier.height(88.dp))
    }
}

/** "mardi 11 novembre", in the app's language. */
private fun dateLine(epochDay: Long): String =
    java.time.LocalDate.ofEpochDay(epochDay).format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM", java.util.Locale.getDefault()))

/**
 * The last seven days, today last: a ring a day, filled as far as the goal was reached (gold once
 * met), over the day's initial. Today's is marked.
 */
@Composable
private fun WeekStrip(days: Map<Long, app.hfd.core.progress.DayStats>, today: Long, goalMs: Long) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        for (day in today - 6..today) {
            val fraction = ((days[day]?.practiceMs ?: 0).toFloat() / maxOf(goalMs, 1)).coerceIn(0f, 1f)
            val initial = java.time.LocalDate.ofEpochDay(day).dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, java.util.Locale.getDefault())
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ProgressRing(fraction, Modifier.size(22.dp), color = if (fraction >= 1f) P.accent else P.textDim)
                Spacer(Modifier.height(5.dp))
                Text(initial.uppercase(), style = Type.label.copy(fontSize = 11.sp), color = if (day == today) P.text else P.textFaint)
            }
        }
    }
}
