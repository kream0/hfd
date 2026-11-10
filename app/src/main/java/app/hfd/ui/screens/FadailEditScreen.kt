package app.hfd.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hfd.Graph
import app.hfd.R
import app.hfd.ui.components.Ic
import app.hfd.ui.components.IconBtn
import app.hfd.ui.components.Loader
import app.hfd.ui.components.PillButton
import app.hfd.ui.components.SectionLabel
import app.hfd.ui.components.Toggle
import app.hfd.ui.components.rangesLabel
import app.hfd.ui.text
import app.hfd.ui.theme.P
import app.hfd.ui.theme.Type

/**
 * Which passages of the reference list the app shows: each with a switch. One switched off is
 * neither listed, offered to learn, reviewed nor played with the others ([Graph.hideFadila]); what
 * was learnt of it is kept, and comes back with it.
 */
@Composable
fun FadailEditScreen(onClose: () -> Unit) {
    val content by Graph.content.content.collectAsStateWithLifecycle()
    BackHandler(onBack = onClose)
    val c = content
    Column(Modifier.fillMaxSize().background(P.background).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Ic.Back, onClose, contentDescription = stringResource(R.string.detail_back))
            Text(stringResource(R.string.fadail_edit_title), style = Type.name, color = P.text, maxLines = 1, modifier = Modifier.weight(1f).padding(start = 4.dp))
        }
        if (c == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Loader() }
            return@Column
        }
        Text(stringResource(R.string.fadail_edit_body), style = Type.label, color = P.textDim, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(stringResource(R.string.fadail_edit_count, c.fadail.size, c.allFadail.size), Modifier.weight(1f))
            PillButton(
                stringResource(R.string.fadail_edit_all),
                onClick = { c.hidden.forEach { Graph.hideFadila(it, hide = false) } },
                enabled = c.fadail.size < c.allFadail.size,
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(c.allFadail, key = { it.id }) { f ->
                val shown = f.id !in c.hidden
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(shown, role = Role.Switch) { show ->
                            // The last one shown stays: a list of none would leave nothing to learn.
                            if (!show && c.fadail.size <= 1) Graph.toast(R.string.fadail_edit_last) else Graph.hideFadila(f.id, hide = !show)
                        }
                        .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(f.title.text, style = Type.name, color = if (shown) P.text else P.textFaint, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(3.dp))
                        Text(rangesLabel(f), style = Type.label, color = if (shown) P.textDim else P.textFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        f.title.ar.orEmpty(),
                        style = Type.arabicTitle.copy(fontSize = 20.sp, lineHeight = 32.sp),
                        color = if (shown) P.textDim else P.textFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 120.dp),
                    )
                    Spacer(Modifier.width(14.dp))
                    // The row is the switch (one target, a whole line): this one only shows it.
                    Toggle(shown, onCheckedChange = null)
                }
            }
            item { Box(Modifier.padding(bottom = 24.dp)) }
        }
    }
}
