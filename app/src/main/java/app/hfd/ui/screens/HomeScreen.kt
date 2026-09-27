package app.hfd.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hfd.Graph
import app.hfd.R
import app.hfd.core.fadail.FadailGrouping
import app.hfd.ui.components.DotLoader
import app.hfd.ui.components.FadilaRow
import app.hfd.ui.components.PillButton
import app.hfd.ui.components.ScreenHeader
import app.hfd.ui.components.SectionLabel
import app.hfd.ui.label
import java.time.LocalDateTime

@Composable
fun HomeScreen(onOpen: (String) -> Unit, onAll: () -> Unit) {
    val content by Graph.content.content.collectAsStateWithLifecycle()
    val settings by Graph.settings.state.collectAsStateWithLifecycle()
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
        val visible = remember(c, settings.showWeak) { c.fadail.filter { it.visible(showWeak = false) } }
        val now = remember { FadailGrouping.now(LocalDateTime.now()) }
        if (now.isNotEmpty()) {
            val suggested = visible.filter { f -> f.occasions.any { it in now } }
            if (suggested.isNotEmpty()) {
                val label = now.map { stringResource(it.label) }.joinToString(" · ")
                SectionLabel(stringResource(R.string.home_now, label), Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp))
                suggested.forEach { FadilaRow(it, progress = 0f, onClick = { onOpen(it.id) }) }
            }
        }
        val daily = visible.filter { "daily" in it.tags && it.occasions.none { o -> o in now } }
        if (daily.isNotEmpty()) {
            SectionLabel(stringResource(R.string.home_daily), Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 4.dp))
            daily.forEach { FadilaRow(it, progress = 0f, onClick = { onOpen(it.id) }) }
        }
        Box(Modifier.padding(20.dp)) { PillButton(stringResource(R.string.home_all), onAll) }
    }
}
