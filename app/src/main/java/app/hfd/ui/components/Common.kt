package app.hfd.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.hfd.ui.theme.P
import app.hfd.ui.theme.Type

data class SheetAction(
    val label: String,
    val icon: ImageVector? = null,
    val destructive: Boolean = false,
    /** When set, tapping opens this follow-up sheet (a picker, a confirmation…) instead of closing. */
    val next: (() -> SheetSpec)? = null,
    val onClick: () -> Unit = {},
)

data class SheetSpec(
    val title: String,
    val subtitle: String? = null,
    val actions: List<SheetAction> = emptyList(),
    val content: (@Composable () -> Unit)? = null,
)

/** Opens a bottom sheet; provided by the root screen. */
val LocalSheets = compositionLocalOf<(SheetSpec) -> Unit> { {} }

/** Closes the open sheet; for a sheet's own [SheetSpec.content]. */
val LocalSheetClose = compositionLocalOf<() -> Unit> { {} }

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(width = 120.dp, height = 60.dp)) {
            DotGrid(Modifier.fillMaxSize(), color = P.outline, spacing = 12.dp, radius = 2.dp)
        }
        Spacer(Modifier.height(18.dp))
        Text(title, style = Type.displaySmall, color = P.text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, style = Type.body, color = P.textDim, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(18.dp))
            action()
        }
    }
}
