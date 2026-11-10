package app.hfd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hfd.Graph
import app.hfd.R
import app.hfd.core.quran.SuraMeta
import app.hfd.ui.components.Equalizer
import app.hfd.ui.components.Loader
import app.hfd.ui.components.Ic
import app.hfd.ui.components.PillButton
import app.hfd.ui.components.PillStyle
import app.hfd.ui.components.ScreenHeader
import app.hfd.ui.components.SectionLabel
import app.hfd.ui.components.Star
import app.hfd.ui.components.card
import app.hfd.ui.theme.P
import app.hfd.ui.theme.Type
import java.text.Normalizer

/**
 * The Qur'an section: every sūra to listen to, with its text. A sūra plays through and the next
 * one follows, to the end of the Qur'an; "resume" starts again where the listening stopped.
 */
@Composable
fun QuranScreen(onOpen: (Int) -> Unit) {
    val content by Graph.content.content.collectAsStateWithLifecycle()
    val np by Graph.nowPlaying.collectAsStateWithLifecycle()
    val ui by Graph.player.state.collectAsStateWithLifecycle()
    val place by Graph.quranPlace.place.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val c = content
    Box(Modifier.fillMaxSize().statusBarsPadding()) {
        if (c == null) {
            Loader(Modifier.align(Alignment.Center))
            return@Box
        }
        val playingSura = np?.session?.sura
        val shown = remember(c, query) { c.suras.filter { matches(it, query) } }
        LazyColumn(Modifier.fillMaxSize().testTag("quran")) {
            item(key = "header") { ScreenHeader(stringResource(R.string.tab_quran)) }
            // Where the listening stopped: the sūra playing now, else the last place.
            val at = np?.takeIf { playingSura != null }?.item?.ref ?: place
            if (at != null) {
                item(key = "resume") {
                    val meta = c.sura(at.sura)
                    val playing = playingSura == at.sura && ui.playWhenReady
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .card()
                            .clickable { onOpen(at.sura) }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(if (playing) R.string.quran_listening else R.string.quran_resume).uppercase(), style = Type.overline, color = P.textDim)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                stringResource(R.string.quran_resume_at, meta.tname, at.aya, meta.ayas),
                                style = Type.name,
                                color = P.text,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        PillButton(
                            stringResource(if (playing) R.string.player_pause else R.string.player_resume),
                            onClick = { if (playingSura == at.sura) Graph.player.togglePlay() else Graph.playSura(at.sura, at) },
                            style = if (playing) PillStyle.Outline else PillStyle.Filled,
                        )
                    }
                }
            }
            item(key = "search") { SearchField(query, onChange = { query = it }) }
            item(key = "count") {
                SectionLabel(
                    if (query.isBlank()) stringResource(R.string.quran_count, c.suras.size) else stringResource(R.string.quran_found, shown.size),
                    Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 4.dp),
                )
            }
            items(shown, key = { it.index }) { s ->
                SuraRow(s, playing = playingSura == s.index, active = playingSura == s.index && ui.playWhenReady, onClick = { onOpen(s.index) })
            }
            item(key = "end") { Box(Modifier.padding(bottom = 24.dp)) }
        }
    }
}

/** Letters and digits only, lower case, without diacritics: "al-Baqara" and "baqara" find البقرة's row. */
private fun fold(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD).filter { it.isLetterOrDigit() && it != 'ʿ' && it != 'ʾ' }.lowercase()

private fun matches(s: SuraMeta, query: String): Boolean {
    val q = fold(query)
    if (q.isEmpty()) return true
    if (q.all { it.isDigit() }) return s.index.toString().startsWith(q)
    return fold(s.tname).contains(q) || fold(s.ename).contains(q) || fold(s.name).contains(q)
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .clip(CircleShape)
            .background(P.surface)
            .border(1.dp, P.outline, CircleShape)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Ic.Search, contentDescription = null, tint = P.textDim, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text(stringResource(R.string.quran_search), style = Type.body, color = P.textFaint, maxLines = 1)
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = Type.body.copy(color = P.text),
                cursorBrush = SolidColor(P.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Icon(
                Ic.Close,
                contentDescription = stringResource(R.string.quran_search_clear),
                tint = P.textDim,
                modifier = Modifier.size(18.dp).clip(CircleShape).clickable { onChange("") },
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SuraRow(s: SuraMeta, playing: Boolean, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Its number in the star, as in a muṣḥaf's margin.
        Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
            Star(Modifier.size(38.dp), color = if (playing) P.accent else P.outline, outline = true)
            if (playing) Equalizer(active, Modifier.size(14.dp))
            else Text(s.index.toString(), style = Type.clock.copy(fontSize = 13.sp), color = P.textDim, maxLines = 1)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(s.tname, style = Type.name, color = P.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Text(
                stringResource(if (s.type == "Medinan") R.string.sura_medinan else R.string.sura_meccan) + " · " +
                    pluralStringResource(R.plurals.ayat_count, s.ayas, s.ayas),
                style = Type.label,
                color = P.textDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            s.name,
            style = Type.arabicTitle.copy(fontSize = 20.sp, lineHeight = 32.sp, textAlign = TextAlign.End),
            color = if (playing) P.text else P.textDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 140.dp),
        )
    }
}
