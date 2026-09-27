package app.hfd.ui

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hfd.Graph
import app.hfd.R
import app.hfd.ui.components.Ic
import app.hfd.ui.components.LocalSheets
import app.hfd.ui.components.SheetAction
import app.hfd.ui.components.SheetHost
import app.hfd.ui.components.SheetSpec
import app.hfd.ui.screens.HomeScreen
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

    CompositionLocalProvider(LocalSheets provides openSheet) {
        Box(Modifier.fillMaxSize().background(P.background)) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    when (app.tab) {
                        Tab.HOME -> HomeScreen()
                        Tab.SETTINGS -> SettingsScreen()
                    }
                }
                BottomNav(tab = app.tab, onTab = app::selectTab)
            }
            ToastHost(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 6.dp))
            SheetHost(sheet, onShow = { sheet = it }) { sheet = null }
        }
    }
}

@Composable
private fun BottomNav(tab: Tab, onTab: (Tab) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Tab.entries.forEach { t ->
            val selected = t == tab
            Column(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onTab(t) }
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(if (selected) P.accent else Color.Transparent),
                )
                Spacer(Modifier.height(5.dp))
                Text(stringResource(t.label).uppercase(), style = Type.labelBold, color = if (selected) P.text else P.textFaint)
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
                .background(if (P.isDark) Color(0xFF1E1E1E) else Color(0xFF111111))
                .padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(P.accent))
            Spacer(Modifier.width(10.dp))
            Text(last, style = Type.label, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
