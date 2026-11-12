package app.hfd.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import app.hfd.playback.PlaySession
import app.hfd.ui.components.Loader
import app.hfd.ui.components.FadilaRow
import app.hfd.ui.components.Ic
import app.hfd.ui.components.IconBtn
import app.hfd.ui.components.PillButton
import app.hfd.ui.components.PillStyle
import app.hfd.ui.components.ScreenHeader
import app.hfd.ui.components.SectionLabel
import app.hfd.ui.uiLanguage

/** Every passage, in the reference list's order, with its progress; and all of them to listen to in a row. */
@Composable
fun FadailScreen(onOpen: (String) -> Unit) {
    val content by Graph.content.content.collectAsStateWithLifecycle()
    val progress by Graph.progress.state.collectAsStateWithLifecycle()
    val np by Graph.nowPlaying.collectAsStateWithLifecycle()
    val ui by Graph.player.state.collectAsStateWithLifecycle()
    val allTitle = stringResource(R.string.play_all_title)
    val lang = uiLanguage
    val now = System.currentTimeMillis()
    val c = content
    Box(Modifier.fillMaxSize().statusBarsPadding()) {
        if (c == null) {
            Loader(Modifier.align(Alignment.Center))
            return@Box
        }
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                ScreenHeader(stringResource(R.string.tab_fadail))
            }
            item {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel(
                        if (c.hidden.isEmpty()) stringResource(R.string.fadail_count, c.fadail.size)
                        else stringResource(R.string.fadail_edit_count, c.fadail.size, c.allFadail.size),
                        Modifier.weight(1f),
                    )
                    val playingAll = np?.session?.fadilaId == PlaySession.ALL && ui.playWhenReady
                    PillButton(
                        stringResource(if (playingAll) R.string.play_all_pause else R.string.play_all),
                        onClick = { if (np?.session?.fadilaId == PlaySession.ALL) Graph.player.togglePlay() else Graph.playAll(allTitle, lang) },
                        icon = Ic.PlaylistPlay,
                        style = PillStyle.Outline,
                    )
                }
            }
            items(c.fadail, key = { it.id }) { f ->
                FadilaRow(f, progress = Stats.fadila(progress, f, Graph.progress.fsrs, now).fraction, onClick = { onOpen(f.id) })
            }
            // (Room under the last row for the bar's button, which floats over this screen's corner.)
            item { Box(Modifier.padding(bottom = 88.dp)) }
        }
    }
}
