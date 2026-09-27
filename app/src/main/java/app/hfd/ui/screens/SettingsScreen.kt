package app.hfd.ui.screens

import android.app.Activity
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
            .padding(bottom = 24.dp),
    ) {
        ScreenHeader(stringResource(R.string.tab_settings).uppercase())

        SettingsSection(stringResource(R.string.settings_updates)) {
            UpdatesSection(settings.autoUpdate)
        }

        SettingsSection(stringResource(R.string.settings_about)) {
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
