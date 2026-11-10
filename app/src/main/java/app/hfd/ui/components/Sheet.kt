package app.hfd.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hfd.ui.theme.P
import app.hfd.ui.theme.Type

/** Bottom sheet overlay: scrim + card sliding up from the bottom. [onShow] swaps in a follow-up sheet. */
@Composable
fun SheetHost(spec: SheetSpec?, onShow: (SheetSpec) -> Unit, onDismiss: () -> Unit) {
    var last by remember { mutableStateOf<SheetSpec?>(null) }
    if (spec != null) last = spec

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(visible = spec != null, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = if (P.isDark) 0.6f else 0.35f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
        }
        AnimatedVisibility(
            visible = spec != null,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            val shown = last ?: return@AnimatedVisibility
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.85f)
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(P.surface)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            ) {
                // Grab handle.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
                    Box(Modifier.size(width = 36.dp, height = 4.dp).clip(CircleShape).background(P.outline))
                }
                Spacer(Modifier.height(16.dp))
                Text(shown.title, style = Type.displaySmall, color = P.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!shown.subtitle.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(shown.subtitle, style = Type.label, color = P.textDim, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(12.dp))
                shown.content?.let {
                    // Keyed so each sheet's content starts fresh (e.g. two name fields in a row).
                    key(shown) { CompositionLocalProvider(LocalSheetClose provides onDismiss) { it() } }
                    Spacer(Modifier.height(8.dp))
                }
                shown.actions.forEach { action ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable {
                                val follow = action.next?.invoke()
                                if (follow != null) {
                                    onShow(follow)
                                } else {
                                    onDismiss()
                                    action.onClick()
                                }
                            }
                            .padding(horizontal = 6.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val tint = if (action.destructive) P.error else P.text
                        if (action.icon != null) {
                            Icon(action.icon, contentDescription = null, tint = if (action.destructive) tint else P.accent, modifier = Modifier.size(20.dp))
                        } else {
                            Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                                Box(Modifier.size(5.dp).clip(CircleShape).background(P.textFaint))
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Text(action.label, style = Type.title.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = tint)
                    }
                }
            }
        }
    }
    // Registered only while open so it outranks handlers of screens underneath (e.g. the player).
    if (spec != null) BackHandler(onBack = onDismiss)
}
