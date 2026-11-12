package app.hfd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hfd.Graph
import app.hfd.R
import app.hfd.core.tajweed.Family
import app.hfd.core.tajweed.Part
import app.hfd.core.tajweed.Rule
import app.hfd.core.tajweed.Span
import app.hfd.core.tajweed.Tajweed
import app.hfd.data.AppSettings
import app.hfd.ui.theme.P
import app.hfd.ui.theme.Type

/**
 * The colours of the rules of tajwīd, as the printed muṣḥafs of tajwīd give them (the reds of the
 * long vowels deepen with their length, green for the ghunna, sky blue for the qalqala, grey for
 * what is not said, deep blue for the heavy rāʾ), each tuned to read on the night and on the paper.
 */
fun tajweedColor(family: Family, dark: Boolean): Color = when (family) {
    Family.GHUNNAH -> if (dark) Color(0xFF63D697) else Color(0xFF16803F)
    Family.QALQALAH -> if (dark) Color(0xFF6DBDFF) else Color(0xFF1272C4)
    Family.MADD_2 -> if (dark) Color(0xFFE0B85A) else Color(0xFF9A6E0A)
    Family.MADD_246 -> if (dark) Color(0xFFFFA347) else Color(0xFFCB6200)
    Family.MADD_45 -> if (dark) Color(0xFFFF7A5C) else Color(0xFFD23A1E)
    Family.MADD_6 -> if (dark) Color(0xFFFF5C8A) else Color(0xFFA5083A)
    Family.SILENT -> if (dark) Color(0xFF7C8A83) else Color(0xFFA29F93)
    Family.TAFKHIM -> if (dark) Color(0xFF9AA5FF) else Color(0xFF27409C)
}

/** The families shown, each with its colour; null where the letters are not coloured. */
class TajweedStyle(val colors: Map<Family, Color>) {
    /** The colours of [text]'s letters, as spans over it. A shorter span (a sign on a letter) shows over a longer one. */
    fun spans(text: String): List<AnnotatedString.Range<SpanStyle>> {
        val spans = Tajweed.annotate(text).filter { shown(it, text) && it.family in colors }
        if (spans.isEmpty()) return emptyList()
        val color = arrayOfNulls<Color>(text.length)
        for (s in spans.sortedByDescending { it.to - it.from }) for (i in s.from until s.to) color[i] = colors[s.family]
        val out = ArrayList<AnnotatedString.Range<SpanStyle>>()
        var i = 0
        while (i < text.length) {
            val c = color[i]
            var e = i + 1
            while (e < text.length && color[e] == c) e++
            if (c != null) out += AnnotatedString.Range(SpanStyle(color = c), i, e)
            i = e
        }
        return out
    }

    companion object {
        /**
         * Of the long vowels of two ḥarakāt, the muṣḥafs colour those written with a small letter
         * (a dagger alif, a small wāw or yāʾ): an alif, a wāw or a yāʾ in full is plain to see.
         */
        fun shown(span: Span, text: String): Boolean =
            span.rule != Rule.MADD_NATURAL || (span.from until span.to).any { text[it] in SMALL }

        private const val SMALL = "ٰۥۦۧ"

        fun of(settings: AppSettings, dark: Boolean): TajweedStyle? =
            if (!settings.tajweed) null
            else TajweedStyle(Family.entries.filter { it.name !in settings.tajweedOff }.associateWith { tajweedColor(it, dark) })
    }
}

/** The colours the Qur'an's text is shown in; null: plain. */
val LocalTajweed = compositionLocalOf<TajweedStyle?> { null }

@Composable
fun ProvideTajweed(content: @Composable () -> Unit) {
    val settings by Graph.settings.state.collectAsStateWithLifecycle()
    val dark = P.isDark
    val style = remember(settings.tajweed, settings.tajweedOff, dark) { TajweedStyle.of(settings, dark) }
    CompositionLocalProvider(LocalTajweed provides style, content = content)
}

private val Family.label: Int
    get() = when (this) {
        Family.GHUNNAH -> R.string.tajweed_family_ghunnah
        Family.QALQALAH -> R.string.tajweed_family_qalqalah
        Family.MADD_2 -> R.string.tajweed_family_madd2
        Family.MADD_246 -> R.string.tajweed_family_madd246
        Family.MADD_45 -> R.string.tajweed_family_madd45
        Family.MADD_6 -> R.string.tajweed_family_madd6
        Family.SILENT -> R.string.tajweed_family_silent
        Family.TAFKHIM -> R.string.tajweed_family_tafkhim
    }

private val Family.body: Int
    get() = when (this) {
        Family.GHUNNAH -> R.string.tajweed_family_ghunnah_body
        Family.QALQALAH -> R.string.tajweed_family_qalqalah_body
        Family.MADD_2 -> R.string.tajweed_family_madd2_body
        Family.MADD_246 -> R.string.tajweed_family_madd246_body
        Family.MADD_45 -> R.string.tajweed_family_madd45_body
        Family.MADD_6 -> R.string.tajweed_family_madd6_body
        Family.SILENT -> R.string.tajweed_family_silent_body
        Family.TAFKHIM -> R.string.tajweed_family_tafkhim_body
    }

/** The order of a muṣḥaf's legend: the long vowels from the longest, then the rest. */
private val LEGEND = listOf(Family.MADD_6, Family.MADD_45, Family.MADD_246, Family.MADD_2, Family.GHUNNAH, Family.QALQALAH, Family.TAFKHIM, Family.SILENT)

/** The colours' legend, which is also where they are chosen: the whole, then each colour. */
fun tajweedSheet(context: android.content.Context) = SheetSpec(
    title = context.getString(R.string.tajweed_title),
    subtitle = context.getString(R.string.tajweed_body),
    content = { TajweedLegend() },
)

@Composable
fun TajweedLegend() {
    val settings by Graph.settings.state.collectAsStateWithLifecycle()
    val dark = P.isDark
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { Graph.settings.update { it.copy(tajweed = !it.tajweed) } }.padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.tajweed_switch), style = Type.title, color = P.text, modifier = Modifier.weight(1f))
            Toggle(settings.tajweed, null)
        }
        // A line of the Book in the colours chosen, to see what they give.
        Graph.content.content.collectAsStateWithLifecycle().value?.quran?.text(SAMPLE)?.let { sample ->
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                QuranText(
                    sample, null, Type.quran(24).copy(textAlign = TextAlign.Center), P.text,
                    Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(16.dp)).background(P.background).padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        for (family in LEGEND) {
            val on = family.name !in settings.tajweedOff
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(enabled = settings.tajweed) {
                        Graph.settings.update { it.copy(tajweedOff = if (on) it.tajweedOff + family.name else it.tajweedOff - family.name) }
                    }
                    .padding(vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val color = tajweedColor(family, dark)
                Box(Modifier.size(18.dp).clip(CircleShape).background(if (settings.tajweed && on) color else color.copy(alpha = 0.25f)))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(family.label), style = Type.title, color = if (settings.tajweed) P.text else P.textDim)
                    Text(stringResource(family.body), style = Type.label, color = P.textDim)
                }
                Spacer(Modifier.width(12.dp))
                Toggle(settings.tajweed && on, null)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.tajweed_note), style = Type.label, color = P.textFaint)
    }
}

/** 2:19 has nearly every colour in one line. */
private val SAMPLE = app.hfd.core.quran.AyahRef(2, 19)

/** A rule's name, as a string resource. */
fun ruleName(rule: Rule): Int = rule.label

private val Rule.label: Int
    get() = when (this) {
        Rule.GHUNNAH -> R.string.tajweed_ghunnah
        Rule.IKHFA -> R.string.tajweed_ikhfa
        Rule.IKHFA_SHAFAWI -> R.string.tajweed_ikhfa_shafawi
        Rule.IQLAB -> R.string.tajweed_iqlab
        Rule.IDGHAM_GHUNNAH -> R.string.tajweed_idgham_ghunnah
        Rule.IDGHAM_NO_GHUNNAH -> R.string.tajweed_idgham_no_ghunnah
        Rule.IDGHAM_SHAFAWI -> R.string.tajweed_idgham_shafawi
        Rule.IDGHAM_MUTAMATHILAYN -> R.string.tajweed_idgham_mutamathilayn
        Rule.IDGHAM_MUTAJANISAYN -> R.string.tajweed_idgham_mutajanisayn
        Rule.IDGHAM_MUTAQARIBAYN -> R.string.tajweed_idgham_mutaqaribayn
        Rule.QALQALAH -> R.string.tajweed_qalqalah
        Rule.MADD_NATURAL -> R.string.tajweed_madd_natural
        Rule.MADD_MUNFASIL -> R.string.tajweed_madd_munfasil
        Rule.MADD_MUTTASIL -> R.string.tajweed_madd_muttasil
        Rule.MADD_LAZIM -> R.string.tajweed_madd_lazim
        Rule.MADD_ARID -> R.string.tajweed_madd_arid
        Rule.MADD_LIN -> R.string.tajweed_madd_lin
        Rule.HAMZAT_WASL -> R.string.tajweed_hamzat_wasl
        Rule.LAM_SHAMSIYYAH -> R.string.tajweed_lam_shamsiyyah
        Rule.SILENT -> R.string.tajweed_silent
        Rule.TAFKHIM -> R.string.tajweed_tafkhim
    }

private val Rule.body: Int
    get() = when (this) {
        Rule.GHUNNAH -> R.string.tajweed_ghunnah_body
        Rule.IKHFA -> R.string.tajweed_ikhfa_body
        Rule.IKHFA_SHAFAWI -> R.string.tajweed_ikhfa_shafawi_body
        Rule.IQLAB -> R.string.tajweed_iqlab_body
        Rule.IDGHAM_GHUNNAH -> R.string.tajweed_idgham_ghunnah_body
        Rule.IDGHAM_NO_GHUNNAH -> R.string.tajweed_idgham_no_ghunnah_body
        Rule.IDGHAM_SHAFAWI -> R.string.tajweed_idgham_shafawi_body
        Rule.IDGHAM_MUTAMATHILAYN -> R.string.tajweed_idgham_mutamathilayn_body
        Rule.IDGHAM_MUTAJANISAYN -> R.string.tajweed_idgham_mutajanisayn_body
        Rule.IDGHAM_MUTAQARIBAYN -> R.string.tajweed_idgham_mutaqaribayn_body
        Rule.QALQALAH -> R.string.tajweed_qalqalah_body
        Rule.MADD_NATURAL -> R.string.tajweed_madd_natural_body
        Rule.MADD_MUNFASIL -> R.string.tajweed_madd_munfasil_body
        Rule.MADD_MUTTASIL -> R.string.tajweed_madd_muttasil_body
        Rule.MADD_LAZIM -> R.string.tajweed_madd_lazim_body
        Rule.MADD_ARID -> R.string.tajweed_madd_arid_body
        Rule.MADD_LIN -> R.string.tajweed_madd_lin_body
        Rule.HAMZAT_WASL -> R.string.tajweed_hamzat_wasl_body
        Rule.LAM_SHAMSIYYAH -> R.string.tajweed_lam_shamsiyyah_body
        Rule.SILENT -> R.string.tajweed_silent_body
        Rule.TAFKHIM -> R.string.tajweed_tafkhim_body
    }

/**
 * The rules of one āya, in the order they come: each with the word it is in (the letters under
 * the rule in its colour), its name and what to do. A letter merged and the one it goes into are
 * one line; the plain long vowels are left out, and the colours the reader switched off.
 */
fun ayahTajweedSheet(context: android.content.Context, title: String, text: String): SheetSpec = SheetSpec(
    title = context.getString(R.string.tajweed_of_aya),
    subtitle = title,
    content = {
        // The legend, and which colours show: from here (and the settings), not a button on every
        // text. Before the rules: a long āya has dozens, and it was out of sight under them.
        val sheets = LocalSheets.current
        Box(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 8.dp)) {
            PillButton(stringResource(R.string.tajweed_open), { sheets(tajweedSheet(context)) }, icon = Ic.Palette)
        }
        AyahTajweed(text)
    },
)

@Composable
private fun AyahTajweed(text: String) {
    val dark = P.isDark
    val all = remember(text) { Tajweed.annotate(text) }
    val settings by Graph.settings.state.collectAsStateWithLifecycle()
    val off = settings.tajweedOff
    // One line a rule: a merging's two letters together.
    val lines = remember(all, off) {
        val out = ArrayList<MutableList<Span>>()
        // (Left out: the joining alifs and the letters never read, which every line has, and the colours switched off.)
        for (s in all.filter { TajweedStyle.shown(it, text) && it.rule != Rule.HAMZAT_WASL && it.rule != Rule.SILENT && it.family.name !in off }) {
            val pair = if (s.part == Part.SOUND && s.rule in MERGING) out.takeLast(3).lastOrNull { it.size == 1 && it[0].rule == s.rule && it[0].part == Part.DROPPED } else null
            if (pair != null) pair += s else out += mutableListOf(s)
        }
        out
    }
    if (lines.isEmpty()) {
        Text(stringResource(R.string.tajweed_none), style = Type.body, color = P.textDim)
        return
    }
    Column(Modifier.fillMaxWidth()) {
        for (group in lines) {
            val rule = group.first().rule
            // The words the rule spans (two for a merging across words).
            var from = group.minOf { it.from }
            var to = group.maxOf { it.to }
            while (from > 0 && text[from - 1] != ' ') from--
            while (to < text.length && text[to] != ' ') to++
            val shown = AnnotatedString(
                text.substring(from, to),
                group.map { AnnotatedString.Range(SpanStyle(color = tajweedColor(it.family, dark)), it.from - from, it.to - from) },
            )
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(tajweedColor(group.last().family, dark)))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(rule.label), style = Type.title, color = P.text)
                    }
                    Text(stringResource(rule.body), style = Type.label, color = P.textDim)
                }
                Spacer(Modifier.width(12.dp))
                Text(shown, style = Type.quran(24), color = P.text, maxLines = 1)
            }
        }
    }
}

private val MERGING = setOf(Rule.IDGHAM_GHUNNAH, Rule.IDGHAM_SHAFAWI)
