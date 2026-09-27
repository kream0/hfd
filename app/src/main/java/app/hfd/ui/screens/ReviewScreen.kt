package app.hfd.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hfd.Graph
import app.hfd.R
import app.hfd.core.fadail.Fadila
import app.hfd.core.playback.GapMode
import app.hfd.core.progress.Event
import app.hfd.core.progress.Mode
import app.hfd.core.progress.Stats
import app.hfd.core.quran.AyahRef
import app.hfd.core.srs.Rating
import app.hfd.data.AppMode
import app.hfd.data.Content
import app.hfd.data.TestState
import app.hfd.playback.PlaySession
import app.hfd.ui.components.DotLoader
import app.hfd.ui.components.EmptyState
import app.hfd.ui.components.Ic
import app.hfd.ui.components.IconBtn
import app.hfd.ui.components.PillButton
import app.hfd.ui.components.PillStyle
import app.hfd.ui.components.RatingButtons
import app.hfd.ui.components.ReciteAyah
import app.hfd.ui.components.StepDots
import app.hfd.ui.components.Visibility
import app.hfd.ui.text
import app.hfd.ui.theme.P
import app.hfd.ui.theme.Type
import app.hfd.ui.uiLanguage

/** Opens a full test of [f]: every āya in order, hidden, recite, reveal, rate. */
fun startTest(f: Fadila) {
    val saved = Graph.sessions.session.value?.test
    val test = if (saved != null && saved.fadilaId == f.id && saved.index < f.size) saved else TestState(f.id)
    Graph.sessions.update { it.copy(mode = AppMode.TEST, fadilaId = f.id, test = test) }
}

/**
 * Review: the āyāt due today across all faḍāʾil (muṣḥaf order), or with [testOf] a full test of
 * one faḍīla. Text hidden → recite → reveal (the āya plays) → rate, which updates FSRS.
 */
@Composable
fun ReviewScreen(testOf: String?, only: List<AyahRef>?, onClose: () -> Unit) {
    val content by Graph.content.content.collectAsStateWithLifecycle()
    val settings by Graph.settings.state.collectAsStateWithLifecycle()
    val progress by Graph.progress.state.collectAsStateWithLifecycle()
    val session by Graph.sessions.session.collectAsStateWithLifecycle()
    val translations by Graph.content.translations.collectAsStateWithLifecycle()
    val lang = settings.translationFor(uiLanguage)
    LaunchedEffect(settings.showTranslation, lang) { if (settings.showTranslation) Graph.content.ensureTranslation(lang) }
    val close = {
        Graph.player.pause()
        onClose()
    }
    BackHandler(onBack = close)

    val c = content
    val test = testOf?.let { c?.fadila(it) }
    // The review queue is fixed when the screen opens (rating an āya makes it not due any more).
    val queue: List<AyahRef>? = remember(c, testOf, only) {
        when {
            c == null -> null
            testOf != null -> c.fadila(testOf)?.ayat
            only != null -> only
            else -> Stats.due(Graph.progress.state.value, System.currentTimeMillis(), Graph.progress.zone)
        }
    }
    var reviewIndex by rememberSaveable { mutableIntStateOf(0) }
    var reviewGood by rememberSaveable { mutableIntStateOf(0) }
    val testState = session?.test?.takeIf { it.fadilaId == testOf }
    val index = if (testOf != null) testState?.index ?: 0 else reviewIndex
    val good = if (testOf != null) testState?.good ?: 0 else reviewGood

    Column(Modifier.fillMaxSize().background(P.background).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Ic.Close, close, contentDescription = stringResource(R.string.close))
            Text(
                if (test != null) stringResource(R.string.test_title, test.title.text) else stringResource(R.string.review_title),
                style = Type.title,
                color = P.text,
                maxLines = 1,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
        }
        if (c == null || queue == null) {
            Box(Modifier.fillMaxSize()) { DotLoader(Modifier.align(Alignment.Center)) }
            return@Column
        }
        if (queue.isEmpty()) {
            EmptyState(stringResource(R.string.review_empty), stringResource(R.string.review_empty_body)) {
                PillButton(stringResource(R.string.close), close)
            }
            return@Column
        }
        if (index >= queue.size) {
            val summary = if (test != null) stringResource(R.string.test_summary, good, queue.size)
            else stringResource(R.string.review_summary, queue.size, good)
            EmptyState(stringResource(R.string.done), summary) {
                PillButton(stringResource(R.string.done), close, style = PillStyle.Accent)
            }
            return@Column
        }
        ReviewCard(
            c = c,
            ref = queue[index],
            position = index,
            total = queue.size,
            arabicSize = settings.arabicSize,
            translation = if (settings.showTranslation) translations[lang]?.text(queue[index]) else null,
            card = progress[queue[index].key].card,
            test = test,
            onRated = { rating ->
                val ok = rating != Rating.AGAIN
                if (test != null) {
                    val next = TestState(test.id, index + 1, good + if (ok) 1 else 0)
                    if (next.index >= queue.size) {
                        Graph.progress.record(Event.Test(System.currentTimeMillis(), test.id, queue.size, next.good))
                    }
                    Graph.sessions.update { it.copy(mode = AppMode.TEST, fadilaId = test.id, test = next) }
                } else {
                    reviewIndex = index + 1
                    if (ok) reviewGood = good + 1
                    Graph.sessions.update { it.copy(mode = AppMode.REVIEW) }
                }
            },
        )
    }
}

@Composable
private fun ReviewCard(
    c: Content,
    ref: AyahRef,
    position: Int,
    total: Int,
    arabicSize: Int,
    translation: String?,
    card: app.hfd.core.srs.SrsCard,
    test: Fadila?,
    onRated: (Rating) -> Unit,
) {
    var revealed by remember(ref, position) { mutableStateOf(false) }
    var hint by remember(ref, position) { mutableStateOf(false) }
    val startedAt = remember(ref, position) { mutableLongStateOf(System.currentTimeMillis()) }
    val sura = c.sura(ref.sura)
    val fadila = test ?: c.fadail.firstOrNull { f -> f.ranges.any { ref in it } }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text(stringResource(R.string.review_position, position + 1, total).uppercase(), style = Type.label, color = P.textDim)
            Spacer(Modifier.height(8.dp))
            if (total <= 40) StepDots(total, position)
            Spacer(Modifier.height(12.dp))
            Text("${sura.tname} · $ref", style = Type.clock, color = P.text)
            fadila?.let { Text(it.title.text, style = Type.body, color = P.textDim) }
            Spacer(Modifier.height(4.dp))
            if (!revealed) Text(stringResource(R.string.review_recite), style = Type.body, color = P.textDim)
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            val visibility = when {
                revealed -> Visibility.FULL
                hint -> Visibility.FIRST_WORD
                else -> Visibility.HIDDEN
            }
            ReciteAyah(ref, c.text(ref), visibility, arabicSize, translation)
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            if (!revealed) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(stringResource(R.string.hint), { hint = !hint })
                    Spacer(Modifier.weight(1f))
                    PillButton(stringResource(R.string.reveal), {
                        revealed = true
                        val title = fadila?.title?.text ?: sura.tname
                        Graph.player.play(
                            PlaySession(fadila?.id ?: "review", title, listOf(ref), 0, 0, repeatEach = 1, repeatRange = 1, gap = GapMode.NONE, basmala = false, tag = "review:$ref"),
                        )
                    }, style = PillStyle.Accent)
                }
            } else {
                RatingButtons(card, onRate = { rating ->
                    val now = System.currentTimeMillis()
                    val mode = if (test != null) Mode.TEST else Mode.REVIEW
                    Graph.progress.record(Event.Rate(now, ref.key, rating, mode, now - startedAt.longValue))
                    onRated(rating)
                })
            }
        }
    }
}
