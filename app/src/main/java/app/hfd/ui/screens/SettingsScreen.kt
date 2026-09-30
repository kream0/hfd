package app.hfd.ui.screens

import android.app.Activity
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import app.hfd.core.progress.LearnOrder
import app.hfd.data.Backup
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import app.hfd.core.playback.GapMode
import app.hfd.ui.components.LocalSheets
import app.hfd.ui.components.gapLabel
import app.hfd.ui.components.reciterSheet
import app.hfd.ui.components.speedLabel
import app.hfd.ui.components.timesLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import app.hfd.core.quran.AyahRef
import app.hfd.data.AppSettings
import app.hfd.data.AppLanguage
import app.hfd.data.AppLocale
import app.hfd.data.ThemeMode
import app.hfd.data.TranslationChoice
import app.hfd.ui.components.Segmented
import app.hfd.ui.components.SettingBlock
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hfd.BuildConfig
import app.hfd.diag.Diag
import app.hfd.Graph
import app.hfd.R
import app.hfd.ui.components.NothingSwitch
import app.hfd.ui.components.PillButton
import app.hfd.ui.components.PillStyle
import app.hfd.ui.components.ScreenHeader
import app.hfd.ui.components.SettingLine
import app.hfd.ui.components.SettingsSection
import app.hfd.ui.theme.P
import app.hfd.ui.theme.Type
import app.hfd.update.UpdateState
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsScreen() {
    val settings by Graph.settings.state.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .testTag("settings")
            .padding(bottom = 24.dp),
    ) {
        ScreenHeader(stringResource(R.string.tab_settings).uppercase())

        SettingsSection(stringResource(R.string.settings_progress)) {
            ProgressSection(settings)
        }

        SettingsSection(stringResource(R.string.settings_listening)) {
            ListeningSection(settings)
        }

        SettingsSection(stringResource(R.string.settings_reminders)) {
            RemindersSection(settings)
        }

        SettingsSection(stringResource(R.string.settings_appearance)) {
            val context = LocalContext.current
            SettingBlock(stringResource(R.string.settings_language), stringResource(R.string.settings_language_body)) {
                Segmented(
                    // Each language in its own name.
                    options = listOf("FRANÇAIS", "ENGLISH", stringResource(R.string.language_system)),
                    selected = settings.language.ordinal,
                    onSelect = { i ->
                        val language = AppLanguage.entries[i]
                        Graph.settings.update { it.copy(language = language) }
                        AppLocale.apply(context.applicationContext, language)
                        // Below Android 13 the activity takes it on being recreated.
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) (context as? Activity)?.recreate()
                    },
                )
            }
            SettingBlock(
                stringResource(R.string.settings_theme),
                stringResource(
                    when (settings.theme) {
                        ThemeMode.SYSTEM -> R.string.theme_system_body
                        ThemeMode.DARK -> R.string.theme_dark_body
                        ThemeMode.PAPER -> R.string.theme_paper_body
                    },
                ),
            ) {
                Segmented(
                    options = listOf(stringResource(R.string.theme_system), stringResource(R.string.theme_dark), stringResource(R.string.theme_paper)),
                    selected = settings.theme.ordinal,
                    onSelect = { i -> Graph.settings.update { it.copy(theme = ThemeMode.entries[i]) } },
                )
            }
        }

        SettingsSection(stringResource(R.string.settings_reading)) {
            ReadingSection(settings)
        }

        SettingsSection(stringResource(R.string.settings_controls)) {
            Text(
                stringResource(R.string.settings_controls_body),
                style = Type.body,
                color = P.textDim,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }

        SettingsSection(stringResource(R.string.settings_updates)) {
            UpdatesSection(settings.autoUpdate)
        }

        SettingsSection(stringResource(R.string.settings_sources)) {
            val uri = LocalUriHandler.current
            Text(
                stringResource(R.string.settings_sources_body),
                style = Type.label,
                color = P.textDim,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Box(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
                PillButton(stringResource(R.string.settings_tanzil_link), { runCatching { uri.openUri("https://tanzil.net") } })
            }
        }

        SettingsSection(stringResource(R.string.settings_about)) {
            SettingLine(stringResource(R.string.settings_diagnostics), stringResource(R.string.settings_diagnostics_body, Diag.session)) {
                NothingSwitch(settings.sendDiagnostics, { on -> Graph.settings.update { it.copy(sendDiagnostics = on) } })
            }
            if (settings.sendDiagnostics) {
                SettingLine(stringResource(R.string.settings_recordings), stringResource(R.string.settings_recordings_body)) {
                    NothingSwitch(settings.sendRecordings, { on -> Graph.settings.update { it.copy(sendRecordings = on) } })
                }
            }
            Text(
                stringResource(R.string.settings_about_body, BuildConfig.VERSION_NAME),
                style = Type.label,
                color = P.textFaint,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
    }
}

@Composable
private fun ProgressSection(settings: AppSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    SettingBlock(stringResource(R.string.settings_goal), stringResource(R.string.settings_goal_body)) {
        val options = AppSettings.GOALS
        Segmented(options.map { "$it′" }, options.indexOf(settings.dailyGoalMin).coerceAtLeast(0), { i ->
            Graph.settings.update { it.copy(dailyGoalMin = options[i]) }
        })
    }
    SettingBlock(stringResource(R.string.learn_order), stringResource(R.string.learn_order_body)) {
        Segmented(
            LearnOrder.entries.map { stringResource(it.shortLabel) },
            settings.learnOrder.ordinal,
            { i -> Graph.settings.update { it.copy(learnOrder = LearnOrder.entries[i]) } },
        )
    }
    SettingBlock(stringResource(R.string.settings_learn_repeats), stringResource(R.string.settings_learn_repeats_body)) {
        val options = AppSettings.LEARN_REPEATS
        Segmented(options.map { "×$it" }, options.indexOf(settings.learnRepeats).coerceAtLeast(0), { i ->
            Graph.settings.update { it.copy(learnRepeats = options[i]) }
        })
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            runCatching { Backup.export(context, uri) }
                .onSuccess { Graph.toast(R.string.backup_exported) }
                .onFailure { Graph.toast(R.string.backup_failed, it.message.orEmpty()) }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { Backup.import(context, uri) }
                .onSuccess { Graph.toast(R.string.backup_imported, it) }
                .onFailure { Graph.toast(R.string.backup_failed, it.message.orEmpty()) }
        }
    }
    SettingBlock(stringResource(R.string.settings_backup), stringResource(R.string.settings_backup_body)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(stringResource(R.string.settings_export), { exporter.launch(Backup.fileName()) })
            PillButton(stringResource(R.string.settings_import), { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) })
        }
    }
}

@Composable
private fun ListeningSection(settings: AppSettings) {
    val sheets = LocalSheets.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    SettingLine(stringResource(R.string.reciter), "${settings.reciterInfo.name} · ${settings.reciterInfo.style}") {
        PillButton(settings.reciterInfo.short, { sheets(reciterSheet(context, settings.reciter)) })
    }
    SettingBlock(stringResource(R.string.repeat_each), stringResource(R.string.repeat_each_body)) {
        val options = AppSettings.REPEAT_EACH
        Segmented(options.map { "×" + timesLabel(it) }, options.indexOf(settings.repeatEach).coerceAtLeast(0), { i ->
            Graph.settings.update { it.copy(repeatEach = options[i]) }
        })
    }
    SettingBlock(stringResource(R.string.repeat_range), stringResource(R.string.repeat_range_body)) {
        val options = AppSettings.REPEAT_RANGE
        Segmented(options.map { "×" + timesLabel(it) }, options.indexOf(settings.repeatRange).coerceAtLeast(0), { i ->
            Graph.settings.update { it.copy(repeatRange = options[i]) }
        })
    }
    SettingBlock(stringResource(R.string.gap), stringResource(R.string.gap_body)) {
        Segmented(
            GapMode.entries.map { if (it == GapMode.NONE) stringResource(R.string.gap_none) else gapLabel(it) },
            settings.gap.ordinal,
            { i -> Graph.settings.update { it.copy(gap = GapMode.entries[i]) } },
        )
    }
    SettingBlock(stringResource(R.string.speed), null) {
        val options = AppSettings.SPEEDS
        Segmented(options.map { speedLabel(it) }, options.indexOf(settings.speed).let { if (it < 0) options.indexOf(1f) else it }, { i ->
            Graph.settings.update { it.copy(speed = options[i]) }
        })
    }
    SettingLine(stringResource(R.string.basmala_setting), stringResource(R.string.basmala_setting_body)) {
        NothingSwitch(settings.basmala, { on -> Graph.settings.update { it.copy(basmala = on) } })
    }
    val files by Graph.audio.files.collectAsStateWithLifecycle()
    var bytes by remember { mutableLongStateOf(0L) }
    LaunchedEffect(files.size) { bytes = withContext(Dispatchers.IO) { Graph.audio.totalBytes() } }
    SettingLine(stringResource(R.string.settings_audio), stringResource(R.string.settings_audio_body, files.size, formatBytes(bytes))) {
        PillButton(stringResource(R.string.settings_audio_delete), {
            scope.launch {
                withContext(Dispatchers.IO) { Graph.audio.deleteAll() }
                Graph.toast(R.string.settings_audio_deleted)
            }
        }, enabled = files.isNotEmpty())
    }
}

private fun formatBytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) String.format(java.util.Locale.US, "%.2f GB", mb / 1024)
    else String.format(java.util.Locale.US, "%.1f MB", mb)
}

@Composable
private fun ReadingSection(settings: AppSettings) {
    val presets = AppSettings.ARABIC_PRESETS
    SettingBlock(stringResource(R.string.settings_arabic_size), stringResource(R.string.settings_arabic_size_body)) {
        Segmented(
            options = listOf("S", "M", "L", "XL"),
            selected = presets.indices.minByOrNull { kotlin.math.abs(presets[it] - settings.arabicSize) } ?: 1,
            onSelect = { i -> Graph.settings.update { it.copy(arabicSize = presets[i]) } },
        )
        val content by Graph.content.content.collectAsStateWithLifecycle()
        content?.quran?.text(AyahRef(1, 1))?.let { basmala ->
            Text(
                basmala,
                style = Type.quran(settings.arabicSize).copy(textAlign = TextAlign.Center),
                color = P.text,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
    }
    SettingLine(stringResource(R.string.settings_translation), stringResource(R.string.settings_translation_body)) {
        NothingSwitch(settings.showTranslation, { on -> Graph.settings.update { it.copy(showTranslation = on) } })
    }
    if (settings.showTranslation) {
        Box(Modifier.padding(horizontal = 20.dp)) {
            Segmented(
                options = listOf(
                    stringResource(R.string.translation_auto),
                    stringResource(R.string.translation_fr),
                    stringResource(R.string.translation_en),
                ),
                selected = settings.translation.ordinal,
                onSelect = { i -> Graph.settings.update { it.copy(translation = TranslationChoice.entries[i]) } },
            )
        }
    }
}

@Composable
private fun UpdatesSection(autoUpdate: Boolean) {
    val context = LocalContext.current
    val state by Graph.updater.state.collectAsStateWithLifecycle()
    val last = Graph.updater.lastChecked
    val status = when (val s = state) {
        UpdateState.Idle, UpdateState.UpToDate ->
            if (last > 0) stringResource(
                R.string.update_status_up_to_date,
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(last)),
            ) else stringResource(R.string.update_status_never)
        UpdateState.Checking -> stringResource(R.string.update_status_checking)
        is UpdateState.Available -> stringResource(R.string.update_status_available, s.remote.versionName)
        is UpdateState.Downloading -> stringResource(R.string.update_status_downloading, s.remote.versionName, (s.progress * 100).toInt())
        is UpdateState.Ready -> stringResource(R.string.update_status_ready, s.remote.versionName)
        is UpdateState.Failed -> stringResource(R.string.update_status_failed, s.message)
    }
    SettingLine("HFD ${Graph.updater.currentVersion}", status) {
        when (state) {
            is UpdateState.Ready -> PillButton(
                stringResource(R.string.update_install),
                { (context as? Activity)?.let { Graph.updater.install(it) } },
                style = PillStyle.Accent,
            )
            is UpdateState.Available -> PillButton(stringResource(R.string.update_get), { Graph.updater.startDownload() })
            UpdateState.Checking, is UpdateState.Downloading -> Unit
            else -> PillButton(stringResource(R.string.update_check), { Graph.updater.check(manual = true) })
        }
    }
    SettingLine(stringResource(R.string.update_auto_title), stringResource(R.string.update_auto_body)) {
        NothingSwitch(autoUpdate, { on -> Graph.settings.update { it.copy(autoUpdate = on) } })
    }
}

private val LearnOrder.shortLabel: Int
    get() = when (this) {
        LearnOrder.SHORTEST -> R.string.order_shortest_short
        LearnOrder.START -> R.string.order_start_short
        LearnOrder.END -> R.string.order_end_short
    }
