package app.hfd.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hfd.Graph
import app.hfd.R
import app.hfd.core.progress.Stats
import app.hfd.ui.components.DotLoader
import app.hfd.ui.components.FadilaRow
import app.hfd.ui.components.ScreenHeader
import app.hfd.ui.components.SectionLabel

/** Every passage, in the order of the reference wird. */
@Composable
fun FadailScreen(onOpen: (String) -> Unit) {
    val content by Graph.content.content.collectAsStateWithLifecycle()
    val progress by Graph.progress.state.collectAsStateWithLifecycle()
    val now = System.currentTimeMillis()
    val c = content
    Box(Modifier.fillMaxSize().statusBarsPadding()) {
        if (c == null) {
            DotLoader(Modifier.align(Alignment.Center))
            return@Box
        }
        LazyColumn(Modifier.fillMaxSize()) {
            item { ScreenHeader(stringResource(R.string.tab_fadail).uppercase()) }
            item {
                SectionLabel(stringResource(R.string.fadail_wird, c.fadail.size), Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 4.dp))
            }
            items(c.fadail, key = { it.id }) { f ->
                FadilaRow(f, progress = Stats.fadila(progress, f, Graph.progress.fsrs, now).fraction, onClick = { onOpen(f.id) })
            }
            item { Box(Modifier.padding(bottom = 24.dp)) }
        }
    }
}
