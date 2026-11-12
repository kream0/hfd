package app.hfd.ui

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.ReportDrawnWhen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hfd.Graph
import app.hfd.R
import app.hfd.core.playback.QuranListening
import app.hfd.core.quran.AyahRef
import app.hfd.ui.components.Ic
import app.hfd.ui.components.LocalSheets
import app.hfd.ui.components.PlayerDock
import app.hfd.ui.components.ProvideTajweed
import app.hfd.ui.components.SheetAction
import app.hfd.ui.components.SheetHost
import app.hfd.ui.components.SheetSpec
import app.hfd.ui.screens.FadailScreen
import app.hfd.ui.screens.FadilaScreen
import app.hfd.ui.screens.HomeScreen
import app.hfd.ui.screens.LearnScreen
import app.hfd.ui.screens.QuranScreen
import app.hfd.ui.screens.FadailEditScreen
import app.hfd.ui.screens.ReciteScreen
import app.hfd.ui.screens.ReviewScreen
import app.hfd.ui.screens.StatsScreen
import app.hfd.ui.screens.SuraScreen
import app.hfd.ui.screens.SettingsScreen
import app.hfd.ui.theme.P
import app.hfd.ui.theme.Type
import app.hfd.update.UpdateState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

@Composable
fun AppRoot(app: AppViewModel) {
    var sheet by remember { mutableStateOf<SheetSpec?>(null) }
    val openSheet: (SheetSpec) -> Unit = remember { { sheet = it } }

    // A new build finished downloading in the background: offer to install it (once per build).
    val update by Graph.updater.state.collectAsStateWithLifecycle()
    val activity = LocalContext.current as? Activity
    val context = LocalContext.current
    LaunchedEffect(update) {
        val ready = update as? UpdateState.Ready ?: return@LaunchedEffect
        if (Graph.updater.dismissedVersion == ready.remote.versionCode || activity == null) return@LaunchedEffect
        Graph.updater.dismissedVersion = ready.remote.versionCode
        sheet = SheetSpec(
            title = context.getString(R.string.update_ready_title, ready.remote.versionName),
            subtitle = ready.remote.headline ?: context.getString(R.string.update_ready_subtitle),
            actions = listOf(
                SheetAction(context.getString(R.string.update_install_now), Ic.Download) { Graph.updater.install(activity) },
                SheetAction(context.getString(R.string.later), Ic.Close) {},
            ),
        )
    }

    // The app is "fully drawn" once the text is loaded: what a start is timed to (smoke.yml).
    val loaded by Graph.content.content.collectAsStateWithLifecycle()
    ReportDrawnWhen { loaded != null }

    CompositionLocalProvider(LocalSheets provides openSheet) {
        ProvideTajweed {
        Box(Modifier.fillMaxSize().background(P.background)) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    when (app.tab) {
                        Tab.HOME -> HomeScreen(app)
                        Tab.FADAIL -> FadailScreen(onOpen = app::openFadila, onEdit = { app.editingFadail = true })
                        Tab.QURAN -> QuranScreen(onOpen = app::openSura, onRecite = { app.reciteAnywhere(near = Graph.reading.place.value) })
                        Tab.STATS -> StatsScreen(onOpen = app::openFadila)
                        Tab.SETTINGS -> SettingsScreen(onEditFadail = { app.editingFadail = true })
                    }
                    FadilaOverlay(app)
                }
                // The one bar: what plays, or the open text to listen to; and the microphone, which
                // recites the passage open, or anywhere in the Qur'an (the app finds what is said:
                // near the place read in the sūra open, when the text says it in several places).
                val open = app.fadila
                PlayerDock(
                    open = open,
                    onOpen = app::openPlaying,
                    onRecite = {
                        val sura = QuranListening.suraOf(open)
                        val passage = if (open != null && sura == null) Graph.content.content.value?.fadila(open) else null
                        if (passage != null) app.recite(passage)
                        else if (sura != null) app.reciteAnywhere(near = Graph.reading.of(sura) ?: AyahRef(sura, 1), show = true)
                        else app.reciteAnywhere(near = Graph.reading.place.value)
                    },
                )
                BottomNav(tab = app.tab, onTab = app::selectTab)
            }
            FlowOverlay(app)
            AnimatedVisibility(app.editingFadail, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                FadailEditScreen(onClose = { app.editingFadail = false })
            }
            ToastHost(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 6.dp))
            SheetHost(sheet, onShow = { sheet = it }) { sheet = null }
        }
        }
    }
}

/** Learn / Review / Test cover the whole screen. */
@Composable
private fun FlowOverlay(app: AppViewModel) {
    var last by remember { mutableStateOf<PracticeFlow?>(null) }
    app.flow?.let { last = it }
    AnimatedVisibility(
        visible = app.flow != null,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
    ) {
        when (val f = last) {
            PracticeFlow.Learn -> LearnScreen(onClose = { app.flow = null })
            is PracticeFlow.Review -> ReviewScreen(testOf = null, only = f.only, onClose = { app.flow = null })
            is PracticeFlow.Test -> ReviewScreen(testOf = f.fadilaId, only = null, onClose = { app.flow = null })
            is PracticeFlow.Recite -> ReciteScreen(onClose = app::closeRecite)
            null -> Unit
        }
    }
}

/** Faḍīla detail (or a sūra of the Qur'an section) slides over the current tab, keeping the bottom navigation visible. */
@Composable
private fun FadilaOverlay(app: AppViewModel) {
    var last by remember { mutableStateOf<String?>(null) }
    app.fadila?.let { last = it }
    AnimatedVisibility(
        visible = app.fadila != null,
        enter = slideInHorizontally { it / 3 } + fadeIn(),
        exit = slideOutHorizontally { it / 3 } + fadeOut(),
    ) {
        last?.let { id ->
            val sura = QuranListening.suraOf(id)
            // Each sūra with its own state (scroll position, what was playing): they follow one another here.
            if (sura != null) key(sura) {
                SuraScreen(
                    sura,
                    target = app.suraTarget?.takeIf { it.sura == sura },
                    onTargetShown = { app.suraTarget = null },
                    onOpen = { n -> app.openSura(n) },
                    onGo = { s, a -> app.openSura(s, a) },
                    onRecite = { from -> if (from != null) app.reciteAnywhere(start = AyahRef(sura, from)) else app.reciteAnywhere(near = Graph.reading.of(sura) ?: AyahRef(sura, 1), show = true) },
                    onBack = { app.fadila = null },
                )
            }
            else FadilaScreen(id, app, onBack = { app.fadila = null })
        }
    }
}

private val Tab.icon: ImageVector
    get() = when (this) {
        Tab.HOME -> Ic.Home
        Tab.FADAIL -> Ic.Star
        Tab.QURAN -> Ic.Book
        Tab.STATS -> Ic.Chart
        Tab.SETTINGS -> Ic.Tune
    }

/** The tabs: an icon over its name; the one shown is lit on a pill. */
@Composable
private fun BottomNav(tab: Tab, onTab: (Tab) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(P.surface)
            .navigationBarsPadding()
            .padding(horizontal = 4.dp, vertical = 6.dp),
    ) {
        Tab.entries.forEach { t ->
            val selected = t == tab
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .selectable(selected, role = Role.Tab) { onTab(t) }
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .size(width = 52.dp, height = 28.dp)
                        .clip(CircleShape)
                        .background(if (selected) P.accent.copy(alpha = if (P.isDark) 0.18f else 0.16f) else Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(t.icon, contentDescription = null, tint = if (selected) P.accent else P.textFaint, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    stringResource(t.label),
                    style = Type.label.copy(fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
                    color = if (selected) P.text else P.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ToastHost(modifier: Modifier = Modifier) {
    var message by remember { mutableStateOf<String?>(null) }
    var last by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        Graph.messages.collectLatest {
            last = it
            message = it
            delay(2600)
            message = null
        }
    }
    AnimatedVisibility(
        visible = message != null,
        modifier = modifier,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
    ) {
        Row(
            Modifier
                .padding(horizontal = 24.dp)
                .clip(CircleShape)
                .background(if (P.isDark) P.surfaceHigh else P.inverse)
                .padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(P.accent))
            Spacer(Modifier.width(10.dp))
            Text(last, style = Type.label, color = if (P.isDark) P.text else P.onInverse, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
