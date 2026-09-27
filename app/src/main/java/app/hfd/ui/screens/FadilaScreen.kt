package app.hfd.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hfd.Graph
import app.hfd.R
import app.hfd.core.fadail.Fadila
import app.hfd.core.quran.AyahRef
import app.hfd.ui.components.Chip
import app.hfd.ui.components.DotLoader
import app.hfd.ui.components.GradeChip
import app.hfd.ui.components.Ic
import app.hfd.ui.components.IconBtn
import app.hfd.ui.components.SectionLabel
import app.hfd.ui.components.rangesLabel
import app.hfd.ui.components.readingItems
import app.hfd.ui.label
import app.hfd.ui.text
import app.hfd.ui.theme.P
import app.hfd.ui.theme.Type
import app.hfd.ui.uiLanguage

@Composable
fun FadilaScreen(
    id: String,
    onBack: () -> Unit,
    listState: LazyListState = rememberLazyListState(),
    current: AyahRef? = null,
    onTapAyah: ((AyahRef) -> Unit)? = null,
    onLongPressAyah: ((AyahRef) -> Unit)? = null,
    header: (@Composable () -> Unit)? = null,
) {
    val content by Graph.content.content.collectAsStateWithLifecycle()
    val settings by Graph.settings.state.collectAsStateWithLifecycle()
    val translations by Graph.content.translations.collectAsStateWithLifecycle()
    val lang = settings.translationFor(uiLanguage)
    LaunchedEffect(settings.showTranslation, lang) {
        if (settings.showTranslation) Graph.content.ensureTranslation(lang)
    }
    BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize().background(P.background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Ic.Back, onBack, contentDescription = stringResource(R.string.detail_back))
        }
        val c = content
        val f = c?.fadila(id)
        if (c == null || f == null) {
            Box(Modifier.fillMaxSize()) { DotLoader(Modifier.align(Alignment.Center)) }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            item(key = "intro") { Intro(f) }
            if (header != null) item(key = "header") { header() }
            readingItems(
                content = c,
                fadila = f,
                arabicSize = settings.arabicSize,
                translation = if (settings.showTranslation) translations[lang] else null,
                current = current,
                onTap = onTapAyah,
                onLongPress = onLongPressAyah,
            )
            item(key = "end") { Spacer(Modifier.height(120.dp)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Intro(f: Fadila) {
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Text(
            f.title.ar.orEmpty(),
            style = Type.arabicTitle.copy(textAlign = TextAlign.Center),
            color = P.text,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Text(f.title.text, style = Type.titleLarge, color = P.text, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        Text(rangesLabel(f).uppercase(), style = Type.label, color = P.textDim, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            f.occasions.forEach { Chip(stringResource(it.label)) }
            GradeChip(f.grading.grade)
            if (f.isLong) Chip(stringResource(R.string.label_long))
        }

        if (!f.grading.grade.acceptable) {
            Spacer(Modifier.height(14.dp))
            Text(stringResource(R.string.detail_weak_warning), style = Type.body, color = P.accent)
        }

        Spacer(Modifier.height(18.dp))
        SectionLabel(stringResource(R.string.detail_virtue))
        Spacer(Modifier.height(8.dp))
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(2.dp).fillMaxHeight().background(P.outline))
            Text(f.virtue.text, style = Type.body.copy(lineHeight = Type.body.lineHeight * 1.1f), color = P.text, modifier = Modifier.padding(start = 12.dp))
        }

        Spacer(Modifier.height(18.dp))
        SectionLabel(stringResource(R.string.detail_sources))
        Spacer(Modifier.height(4.dp))
        f.sources.forEach { s ->
            Row(
                Modifier.fillMaxWidth().clickable { runCatching { uri.openUri(s.url) } }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("${s.collection} ${s.number}", style = Type.title, color = P.text)
                    Text(s.narrator.uppercase(), style = Type.label, color = P.textDim)
                }
                Text("↗", style = Type.title, color = P.textDim)
            }
        }

        Spacer(Modifier.height(12.dp))
        SectionLabel(stringResource(R.string.detail_grading))
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.detail_graded_by, stringResource(f.grading.grade.label), f.grading.by),
            style = Type.title,
            color = if (f.grading.grade.acceptable) P.text else P.accent,
        )
        f.grading.note?.let {
            Spacer(Modifier.height(4.dp))
            Text(it.text, style = Type.body, color = P.textDim)
        }
        Spacer(Modifier.height(10.dp))
    }
}
