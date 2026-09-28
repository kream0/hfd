package app.hfd.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import app.hfd.Graph
import app.hfd.R
import app.hfd.core.fadail.Fadila
import app.hfd.core.progress.LearnStep
import app.hfd.core.quran.AyahRef
import app.hfd.ui.screens.startLearning
import app.hfd.ui.screens.startTest

enum class Tab(@StringRes val label: Int) {
    HOME(R.string.tab_home),
    FADAIL(R.string.tab_fadail),
    STATS(R.string.tab_stats),
    SETTINGS(R.string.tab_settings),
}

/** Full-screen practice flows, over everything else. */
sealed interface PracticeFlow {
    data object Learn : PracticeFlow
    /** Review what's due, or only [only] (e.g. "test this āya"). */
    data class Review(val only: List<AyahRef>? = null) : PracticeFlow
    data class Test(val fadilaId: String) : PracticeFlow
    /** Reciting a passage aloud, checked by speech recognition (Graph.recite). */
    data class Recite(val fadilaId: String) : PracticeFlow
}

/** App-level navigation state; saved so the app reopens exactly where it was left. */
class AppViewModel : ViewModel() {
    private var _tab by mutableStateOf(Tab.HOME)
    private var _fadila by mutableStateOf<String?>(null)
    private var _flow by mutableStateOf<PracticeFlow?>(null)

    var tab: Tab
        get() = _tab
        set(v) {
            _tab = v
            persist()
        }

    /** Faḍīla open in the detail view, over the current tab. */
    var fadila: String?
        get() = _fadila
        set(v) {
            _fadila = v
            persist()
        }

    var flow: PracticeFlow?
        get() = _flow
        set(v) {
            _flow = v
            persist()
        }

    init {
        Graph.sessions.session.value?.let { s ->
            _tab = Tab.entries.firstOrNull { it.name == s.openTab } ?: Tab.HOME
            _fadila = s.openFadila
            _flow = when {
                s.openFlow == "learn" && s.learn != null && s.learn.step != LearnStep.DONE -> PracticeFlow.Learn
                s.openFlow?.startsWith("test:") == true && s.test != null -> PracticeFlow.Test(s.openFlow!!.removePrefix("test:"))
                else -> null
            }
        }
    }

    private fun persist() {
        val flowName = when (val f = _flow) {
            PracticeFlow.Learn -> "learn"
            is PracticeFlow.Review -> "review"
            is PracticeFlow.Test -> "test:${f.fadilaId}"
            // Not resumed after a restart: a recitation starts again from its first āya.
            is PracticeFlow.Recite -> null
            null -> null
        }
        Graph.sessions.update { it.copy(openTab = _tab.name, openFadila = _fadila, openFlow = flowName) }
    }

    fun selectTab(t: Tab) {
        fadila = null
        tab = t
    }

    fun openFadila(id: String) {
        fadila = id
    }

    fun learn(f: Fadila, fromIndex: Int? = null) {
        startLearning(f, fromIndex)
        flow = PracticeFlow.Learn
    }

    fun review(only: List<AyahRef>? = null) {
        flow = PracticeFlow.Review(only)
    }

    /** Recite passage [f] aloud (its chosen sub-range), checked by speech recognition. */
    fun recite(f: Fadila) {
        val range = Graph.ranges.get(f.id, f.size)
        Graph.openRecite(f.id, f.ayat.subList(range.from, range.to + 1))
        flow = PracticeFlow.Recite(f.id)
    }

    fun test(f: Fadila) {
        startTest(f)
        flow = PracticeFlow.Test(f.id)
    }

    /** From the notification: show what's playing (or what would resume). */
    fun openPlaying() {
        val id = Graph.nowPlaying.value?.session?.fadilaId ?: return
        if (Graph.content.content.value?.fadila(id) != null) {
            flow = null
            fadila = id
        }
    }
}
