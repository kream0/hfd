package app.hfd.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hfd.Graph
import app.hfd.R
import app.hfd.data.AppSettings
import app.hfd.ui.components.LocalSheets
import app.hfd.ui.components.NothingSwitch
import app.hfd.ui.components.SheetSpec
import app.hfd.ui.theme.P
import app.hfd.ui.theme.Type
import java.util.Locale

private fun hhmm(minutes: Int) = String.format(Locale.US, "%02d:%02d", minutes / 60, minutes % 60)

@Composable
fun RemindersSection(settings: AppSettings) {
    val context = LocalContext.current
    val sheets = LocalSheets.current
    // A reminder needs notifications: ask again when one is switched on (Android 13+).
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Graph.toast(R.string.notifications_denied)
    }
    fun toggle(on: Boolean, set: (AppSettings) -> AppSettings) {
        Graph.settings.update(set)
        if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    ReminderLine(stringResource(R.string.remind_reviews), settings.remindReviews, settings.reviewsAt,
        onToggle = { on -> toggle(on) { it.copy(remindReviews = on) } },
        onTime = { sheets(timeSheet(context, { it.reviewsAt }, { s, m -> s.copy(reviewsAt = m) })) })
}

@Composable
private fun ReminderLine(title: String, on: Boolean, at: Int, onToggle: (Boolean) -> Unit, onTime: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.title, color = P.text)
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier.clip(CircleShape).border(1.dp, P.outline, CircleShape).clickable(onClick = onTime)
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            ) {
                Text(stringResource(R.string.remind_at, hhmm(at)).uppercase(), style = Type.labelBold, color = if (on) P.text else P.textDim)
            }
        }
        Spacer(Modifier.width(12.dp))
        NothingSwitch(on, onToggle)
    }
}

/** Pick a time in steps of 15 minutes (and hours); [get] / [set] read and write the setting. */
fun timeSheet(context: Context, get: (AppSettings) -> Int, set: (AppSettings, Int) -> AppSettings) = SheetSpec(
    title = context.getString(R.string.time_title),
    content = {
        val settings by Graph.settings.state.collectAsStateWithLifecycle()
        val value = get(settings)
        fun pick(delta: Int) = Graph.settings.update { set(it, Math.floorMod(get(it) + delta, 24 * 60)) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            TimeStep("−1h") { pick(-60) }
            TimeStep("−15") { pick(-15) }
            Text(hhmm(value), style = Type.display, color = P.text, modifier = Modifier.padding(horizontal = 8.dp))
            TimeStep("+15") { pick(15) }
            TimeStep("+1h") { pick(60) }
        }
    },
)

@Composable
private fun TimeStep(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, P.outline, RoundedCornerShape(14.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = Type.labelBold, color = P.text)
    }
}
