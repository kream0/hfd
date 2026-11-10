package app.hfd.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hfd.ui.theme.P
import app.hfd.ui.theme.Type

/** [Filled] and [Accent]: the screen's main action; [Outline]: the others. */
enum class PillStyle { Filled, Accent, Outline }

@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    style: PillStyle = PillStyle.Outline,
    enabled: Boolean = true,
) {
    val (bg, fg, border) = when (style) {
        PillStyle.Filled, PillStyle.Accent -> Triple(P.primary, P.onPrimary, P.primary)
        PillStyle.Outline -> Triple(Color.Transparent, P.text, P.outline)
    }
    Row(
        modifier
            .height(44.dp)
            .clip(CircleShape)
            .background(if (enabled) bg else bg.copy(alpha = bg.alpha * 0.35f))
            .border(1.dp, if (enabled) border else border.copy(alpha = 0.35f), CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = Type.button, color = if (enabled) fg else fg.copy(alpha = 0.5f), maxLines = 1)
    }
}

@Composable
fun IconBtn(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = P.text,
    size: Dp = 40.dp,
    iconSize: Dp = 22.dp,
    bordered: Boolean = false,
    contentDescription: String? = null,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .then(if (bordered) Modifier.border(1.dp, P.outline, CircleShape) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** Pill segmented control; the selected segment is lit in the accent. */
@Composable
fun Segmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .border(1.dp, P.outline, CircleShape)
            .padding(3.dp),
    ) {
        options.forEachIndexed { i, label ->
            val isSelected = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(if (isSelected) P.accent.copy(alpha = if (P.isDark) 0.2f else 0.16f) else Color.Transparent)
                    .then(if (isSelected) Modifier.border(1.dp, P.accent.copy(alpha = 0.7f), CircleShape) else Modifier)
                    .selectable(isSelected, role = Role.RadioButton) { onSelect(i) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = Type.button.copy(fontSize = 14.sp),
                    color = if (isSelected) P.text else P.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A switch: an outlined pill, filled when on. Without [onCheckedChange] it only shows (its row is the switch). */
@Composable
fun Toggle(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier) {
    val x by animateDpAsState(if (checked) 22.dp else 3.dp, label = "switch")
    Box(
        modifier
            .size(width = 46.dp, height = 26.dp)
            .clip(CircleShape)
            .background(if (checked) P.primary else Color.Transparent)
            .border(1.dp, if (checked) P.primary else P.outline, CircleShape)
            .then(if (onCheckedChange != null) Modifier.toggleable(checked, role = Role.Switch, onValueChange = onCheckedChange) else Modifier),
    ) {
        Box(
            Modifier
                .offset(x = x, y = 3.dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(if (checked) P.onPrimary else P.textDim),
        )
    }
}

@Composable
fun Stepper(value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepButton("−", enabled = value > range.first) { onChange(value - 1) }
        Text(
            value.toString(),
            style = Type.clock,
            color = P.text,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        StepButton("+", enabled = value < range.last) { onChange(value + 1) }
    }
}

@Composable
private fun StepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .border(1.dp, P.outline, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = Type.title, color = if (enabled) P.text else P.textFaint)
    }
}

/** A card: the raised surface, round-cornered, with a hairline so it stands off the paper too. */
@Composable
fun Modifier.card(radius: Dp = 22.dp): Modifier =
    clip(RoundedCornerShape(radius)).background(P.surface).border(1.dp, P.outline.copy(alpha = if (P.isDark) 0.6f else 0.8f), RoundedCornerShape(radius))

/** A section's heading: the star, then small capitals. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = P.textDim) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Star(Modifier.size(9.dp))
        Spacer(Modifier.width(8.dp))
        Text(text.uppercase(), style = Type.overline, color = color)
    }
}

@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = Type.display, color = P.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing()
    }
}
