package app.hfd.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.hfd.ui.theme.P
import app.hfd.ui.theme.Type

@Composable
fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(18.dp))
    SectionLabel(title, Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
    Column(Modifier.fillMaxWidth(), content = content)
}

/** Title, a full-width control underneath, then a description. */
@Composable
fun SettingBlock(title: String, description: String?, control: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(title, style = Type.title, color = P.text)
        Spacer(Modifier.height(8.dp))
        control()
        if (!description.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(description, style = Type.label, color = P.textDim)
        }
    }
}

/** Title + description on the left, a compact control on the right. */
@Composable
fun SettingLine(title: String, description: String?, control: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.title, color = P.text)
            if (!description.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(description, style = Type.label, color = P.textDim)
            }
        }
        Spacer(Modifier.width(12.dp))
        control()
    }
}
