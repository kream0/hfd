package app.hfd.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.hfd.ui.theme.P
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The eight-pointed star of the muṣḥaf's margins (two squares, one turned an eighth), around
 * [center] with points at [radius]: the app's ornament.
 */
fun starPath(center: Offset, radius: Float): Path {
    // Where the two squares' sides cross, between two points.
    val inner = radius * (cos(PI / 4) / cos(PI / 8)).toFloat()
    return Path().apply {
        for (i in 0 until 16) {
            val r = if (i % 2 == 0) radius else inner
            val a = -PI / 2 + i * PI / 8
            val x = center.x + (cos(a) * r).toFloat()
            val y = center.y + (sin(a) * r).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
}

/** The star, filled or as an outline. */
@Composable
fun Star(modifier: Modifier = Modifier.size(8.dp), color: Color = P.accent, outline: Boolean = false) {
    Canvas(modifier) {
        val r = size.minDimension / 2
        val stroke = (size.minDimension * 0.045f).coerceAtLeast(1.dp.toPx())
        if (outline) drawPath(starPath(center, r - stroke / 2), color, style = Stroke(stroke))
        else drawPath(starPath(center, r), color)
    }
}

/**
 * Seek bar: a rounded track filled up to the playhead, which is a gold bead. Tap or drag to
 * seek. [secondary] tints the track ahead up to that fraction (what is saved or buffered).
 */
@Composable
fun SeekBar(
    progress: Float,
    modifier: Modifier = Modifier,
    secondary: Float = 0f,
    onSeek: ((Float) -> Unit)? = null,
    height: Dp = 20.dp,
    thickness: Dp = 4.dp,
    activeColor: Color = P.accent,
    secondaryColor: Color = P.textFaint,
    inactiveColor: Color = P.track,
    showHead: Boolean = true,
) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val seek by rememberUpdatedState(onSeek)
    val shown = (drag ?: progress).coerceIn(0f, 1f)

    val input = if (onSeek != null) {
        Modifier
            .pointerInput(Unit) {
                detectTapGestures { offset -> seek?.invoke((offset.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { drag = (it.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = {
                        drag?.let { f -> seek?.invoke(f) }
                        drag = null
                    },
                    onDragCancel = { drag = null },
                ) { change, _ ->
                    change.consume()
                    drag = (change.position.x / size.width).coerceIn(0f, 1f)
                }
            }
    } else {
        Modifier
    }

    Canvas(modifier.fillMaxWidth().height(height).then(input)) {
        val t = thickness.toPx()
        val head = if (showHead) t * 1.6f else t / 2
        val cy = size.height / 2
        val x0 = head
        val x1 = size.width - head
        fun line(from: Float, to: Float, color: Color) {
            if (to > from) drawLine(color, Offset(x0 + (x1 - x0) * from, cy), Offset(x0 + (x1 - x0) * to, cy), t, StrokeCap.Round)
        }
        line(0f, 1f, inactiveColor)
        line(shown, secondary.coerceIn(0f, 1f), secondaryColor)
        line(0f, shown, activeColor)
        if (showHead) drawCircle(activeColor, head, Offset(x0 + (x1 - x0) * shown, cy))
    }
}

/** Three beads, one after the other brighter: something is loading. */
@Composable
fun Loader(modifier: Modifier = Modifier, color: Color = P.accent) {
    val t = rememberInfiniteTransition(label = "loader")
    val pos by t.animateFloat(
        initialValue = -1f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "pos",
    )
    Canvas(modifier.size(width = 40.dp, height = 10.dp)) {
        val cell = size.width / 3
        val r = min(cell, size.height) * 0.3f
        for (i in 0 until 3) {
            val a = (1f - abs(pos - i) / 2f).coerceIn(0.2f, 1f)
            drawCircle(color.copy(alpha = a), r, Offset(i * cell + cell / 2, size.height / 2))
        }
    }
}

/** Three bars rising and falling while [playing]: this is what plays. */
@Composable
fun Equalizer(playing: Boolean, modifier: Modifier = Modifier.size(18.dp), color: Color = P.accent) {
    val t = rememberInfiniteTransition(label = "eq")
    val a by t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(620, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    val b by t.animateFloat(1f, 0.35f, infiniteRepeatable(tween(480, easing = LinearEasing), RepeatMode.Reverse), label = "b")
    val c by t.animateFloat(0.45f, 0.95f, infiniteRepeatable(tween(760, easing = LinearEasing), RepeatMode.Reverse), label = "c")
    Canvas(modifier) {
        val levels = if (playing) floatArrayOf(a, b, c) else floatArrayOf(0.35f, 0.6f, 0.45f)
        val w = size.width / 5
        for (i in 0 until 3) {
            val h = size.height * levels[i]
            drawRoundRect(
                color,
                topLeft = Offset(w * (0.5f + i * 1.5f), size.height - h),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2),
            )
        }
    }
}

/**
 * A ring filled clockwise from the top up to [progress]: a goal, a passage's memorisation, a
 * download. [spinning]: an arc going round instead (waiting).
 */
@Composable
fun ProgressRing(
    progress: Float,
    modifier: Modifier = Modifier.size(18.dp),
    color: Color = P.accent,
    offColor: Color = P.track,
    spinning: Boolean = false,
) {
    val turn = if (spinning) {
        rememberInfiniteTransition(label = "ring")
            .animateFloat(0f, 360f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "spin").value
    } else {
        0f
    }
    Canvas(modifier) {
        val stroke = size.minDimension * 0.14f
        val inset = stroke / 2
        val arc = Size(size.width - stroke, size.height - stroke)
        drawArc(offColor, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
        if (spinning) {
            drawArc(color, turn - 90f, 80f, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
        } else if (progress > 0f) {
            drawArc(color, -90f, 360f * progress.coerceIn(0f, 1f), false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
        }
    }
}

/** A lattice of small stars: what stands where the text is hidden, or where there is nothing yet. */
@Composable
fun StarField(modifier: Modifier = Modifier, color: Color = P.track, spacing: Dp = 16.dp, radius: Dp = 3.dp) {
    Canvas(modifier) { starField(color, spacing.toPx(), radius.toPx()) }
}

private fun DrawScope.starField(color: Color, s: Float, r: Float) {
    var row = 0
    var y = s / 2
    while (y < size.height) {
        var x = if (row % 2 == 0) s / 2 else s
        while (x < size.width) {
            drawPath(starPath(Offset(x, y), r), color)
            x += s
        }
        y += s * 0.86f
        row++
    }
}
