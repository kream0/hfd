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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import app.hfd.core.progress.LearnFlow
import app.hfd.core.progress.LearnInput
import app.hfd.core.progress.LearnState
import app.hfd.core.progress.LearnStep
import app.hfd.core.progress.Mode
import app.hfd.data.AppMode
import app.hfd.data.Content
import app.hfd.playback.PlaySession
import app.hfd.ui.components.KeepScreenOn
import app.hfd.ui.components.ChoiceButton
import app.hfd.ui.components.DotLoader
import app.hfd.ui.components.EmptyState
import app.hfd.ui.components.Ic
import app.hfd.ui.components.IconBtn
import app.hfd.ui.components.PillButton
import app.hfd.ui.components.PillStyle
import app.hfd.ui.components.RatingButtons
import app.hfd.ui.components.ReciteAyah
import app.hfd.ui.components.SectionLabel
import app.hfd.ui.components.StepDots
import app.hfd.ui.components.Visibility
import app.hfd.ui.text
import app.hfd.ui.theme.P
import app.hfd.ui.theme.Type
import app.hfd.ui.uiLanguage

/** Tag of the audio a Learn step plays, to recognise when it has finished. */
private fun tagOf(s: LearnState) = "learn:${s.fadilaId}:${s.index}:${s.step}:${s.attempt}"

/** Starts (or resumes) learning [fadila] over its chosen range. */
fun startLearning(fadila: Fadila, fromIndex: Int? = null) {
    val range = Graph.ranges.get(fadila.id, fadila.size)
    val progress = Graph.progress.state.value
    val saved = Graph.sessions.session.value?.learn
    val state = when {
        fromIndex != null -> LearnState(fadila.id, minOf(range.from, fromIndex), maxOf(range.to, fromIndex), fromIndex)
        saved != null && saved.fadilaId == fadila.id && saved.step != LearnStep.DONE -> saved
        else -> LearnFlow.start(fadila.id, range.from, range.to) { i -> !progress[fadila.ayat[i].key].started }
    }
    Graph.sessions.update { it.copy(mode = AppMode.LEARN, fadilaId = fadila.id, learn = state) }
}

@Composable
fun LearnScreen(onClose: () -> Unit) {
    KeepScreenOn()
    val content by Graph.content.content.collectAsStateWithLifecycle()
    val session by Graph.sessions.session.collectAsStateWithLifecycle()
    val settings by Graph.settings.state.collectAsStateWithLifecycle()
    val progress by Graph.progress.state.collectAsStateWithLifecycle()
    val translations by Graph.content.translations.collectAsStateWithLifecycle()
    val np by Graph.nowPlaying.collectAsStateWithLifecycle()
    val lang = settings.translationFor(uiLanguage)
    LaunchedEffect(settings.showTranslation, lang) { if (settings.showTranslation) Graph.content.ensureTranslation(lang) }

    val close = {
        if (np?.session?.tag?.startsWith("learn:") == true) Graph.player.pause()
        onClose()
    }
    BackHandler(onBack = close)

    val c = content
    val s = session?.learn
    val f = s?.let { c?.fadila(it.fadilaId) }
    // Saved for a passage that no longer exists (the list changed): nothing to resume.
    LaunchedEffect(c, s, f) { if (c != null && s != null && f == null) onClose() }
    Column(Modifier.fillMaxSize().background(P.background).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Ic.Close, close, contentDescription = stringResource(R.string.close))
            Text(
                f?.let { stringResource(R.string.learn_title, it.title.text) }.orEmpty(),
                style = Type.title,
                color = P.text,
                maxLines = 1,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
        }
        if (c == null || s == null || f == null) {
            Box(Modifier.fillMaxSize()) { DotLoader(Modifier.align(Alignment.Center)) }
            return@Column
        }
        LearnBody(c, f, s, settings.arabicSize, if (settings.showTranslation) translations[lang] else null, np, progress, settings.learnRepeats, close)
    }
}

@Composable
private fun LearnBody(
    c: Content,
    f: Fadila,
    s: LearnState,
    arabicSize: Int,
    translation: app.hfd.core.quran.QuranText?,
    np: app.hfd.playback.NowPlaying?,
    progress: app.hfd.core.progress.ProgressState,
    repeats: Int,
    close: () -> Unit,
) {
    val ayat = f.ayat
    val ref = ayat[s.index]
    val title = f.title.text
    var hint by remember(s.index, s.step) { mutableStateOf(false) }
    var reciteStart by remember { mutableLongStateOf(System.currentTimeMillis()) }

    fun dispatch(input: LearnInput) {
        val next = LearnFlow.next(s, input)
        if (next != s) Graph.sessions.update { it.copy(mode = AppMode.LEARN, fadilaId = f.id, learn = next) }
    }

    fun play(from: Int, to: Int, each: Int, gap: GapMode) {
        Graph.player.play(
            PlaySession(f.id, title, ayat.subList(from, to + 1), from, to, repeatEach = each, repeatRange = 1, gap = gap, basmala = false, tag = tagOf(s)),
        )
    }

    // Each step plays its own audio when it starts.
    LaunchedEffect(s.index, s.step, s.attempt) {
        when (s.step) {
            LearnStep.LISTEN -> play(s.index, s.index, repeats, GapMode.NONE)
            LearnStep.REPEAT -> play(s.index, s.index, repeats, GapMode.ONE)
            LearnStep.FIRST_WORDS -> play(s.index, s.index, 1, GapMode.ONE)
            LearnStep.REVEALED -> play(s.index, s.index, 1, GapMode.NONE)
            LearnStep.CUMULATIVE_REVEALED -> play(s.from, s.index, 1, GapMode.NONE)
            LearnStep.RECITE, LearnStep.CUMULATIVE -> {
                Graph.player.pause()
                reciteStart = System.currentTimeMillis()
            }
            LearnStep.DONE -> Graph.player.pause()
        }
    }
    // Listening steps move on by themselves when their audio ends.
    LaunchedEffect(np?.ended, np?.session?.tag) {
        val auto = s.step == LearnStep.LISTEN || s.step == LearnStep.REPEAT || s.step == LearnStep.FIRST_WORDS
        if (auto && np?.ended == true && np.session.tag == tagOf(s)) dispatch(LearnInput.Next)
    }

    if (s.step == LearnStep.DONE) {
        EmptyState(stringResource(R.string.learn_done), stringResource(R.string.learn_done_body)) {
            PillButton(stringResource(R.string.done), close, style = PillStyle.Accent)
        }
        return
    }

    val (stepTitle, stepBody) = when (s.step) {
        LearnStep.LISTEN -> R.string.learn_step_listen to stringResource(R.string.learn_step_listen_body)
        LearnStep.REPEAT -> R.string.learn_step_repeat to stringResource(R.string.learn_step_repeat_body)
        LearnStep.FIRST_WORDS -> R.string.learn_step_first_words to stringResource(R.string.learn_step_first_words_body)
        LearnStep.RECITE -> R.string.learn_step_recite to stringResource(R.string.learn_step_recite_body)
        LearnStep.REVEALED -> R.string.learn_step_revealed to ""
        LearnStep.CUMULATIVE -> R.string.learn_step_cumulative to stringResource(R.string.learn_step_cumulative_body, ayat[s.from].toString(), ref.toString())
        LearnStep.CUMULATIVE_REVEALED -> R.string.learn_step_cumulative_revealed to ""
        LearnStep.DONE -> R.string.learn_done to ""
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text(
                stringResource(R.string.learn_position, s.index - s.from + 1, s.to - s.from + 1, ref.toString()).uppercase(),
                style = Type.label,
                color = P.textDim,
            )
            Spacer(Modifier.height(10.dp))
            val stepIndex = LearnFlow.MAIN_STEPS.indexOf(s.step).let { if (it < 0) LearnFlow.MAIN_STEPS.size else it }
            StepDots(LearnFlow.MAIN_STEPS.size + if (s.index > s.from) 1 else 0, stepIndex)
            Spacer(Modifier.height(14.dp))
            SectionLabel(stringResource(stepTitle))
            if (stepBody.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(stepBody, style = Type.body, color = P.textDim)
            }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val cumulative = s.step == LearnStep.CUMULATIVE || s.step == LearnStep.CUMULATIVE_REVEALED
            val shown = if (cumulative) (s.from..s.index) else (s.index..s.index)
            for (i in shown) {
                val r = ayat[i]
                val visibility = when (s.step) {
                    LearnStep.LISTEN, LearnStep.REPEAT, LearnStep.REVEALED, LearnStep.CUMULATIVE_REVEALED -> Visibility.FULL
                    LearnStep.FIRST_WORDS -> Visibility.FIRST_WORD
                    else -> if (hint) Visibility.FIRST_WORD else Visibility.HIDDEN
                }
                ReciteAyah(r, c.text(r), visibility, arabicSize, translation?.text(r))
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            when (s.step) {
                LearnStep.LISTEN, LearnStep.REPEAT, LearnStep.FIRST_WORDS -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(stringResource(R.string.replay), {
                        Graph.sessions.update { it.copy(learn = s.copy(attempt = s.attempt + 1)) }
                    })
                    PillButton(stringResource(R.string.skip), { dispatch(LearnInput.Skip) })
                    Spacer(Modifier.weight(1f))
                    PillButton(stringResource(R.string.next), { dispatch(LearnInput.Next) }, style = PillStyle.Filled)
                }
                LearnStep.RECITE, LearnStep.CUMULATIVE -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(stringResource(R.string.hint), { hint = !hint })
                    Spacer(Modifier.weight(1f))
                    PillButton(stringResource(R.string.reveal), { dispatch(LearnInput.Reveal) }, style = PillStyle.Accent)
                }
                LearnStep.REVEALED -> RatingButtons(progress[ref.key].card, onRate = { rating ->
                    Graph.progress.record(Event.Rate(System.currentTimeMillis(), ref.key, rating, Mode.LEARN, System.currentTimeMillis() - reciteStart))
                    dispatch(LearnInput.Rated(rating))
                })
                LearnStep.CUMULATIVE_REVEALED -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    fun chain(ok: Boolean) {
                        val keys = (s.from..s.index).map { ayat[it].key }
                        Graph.progress.record(Event.Chain(System.currentTimeMillis(), keys, ok, System.currentTimeMillis() - reciteStart))
                        dispatch(LearnInput.ChainResult(ok))
                    }
                    ChoiceButton(stringResource(R.string.chain_mistakes), { chain(false) }, Modifier.weight(1f))
                    ChoiceButton(stringResource(R.string.chain_ok), { chain(true) }, Modifier.weight(1f), strong = true)
                }
                LearnStep.DONE -> Unit
            }
        }
    }
}
