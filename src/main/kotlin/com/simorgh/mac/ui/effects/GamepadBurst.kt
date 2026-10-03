package com.simorgh.mac.ui.effects

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import com.simorgh.mac.str.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.simorgh.mac.R
import com.simorgh.mac.ui.theme.LocalReducedMotion
import com.simorgh.mac.ui.theme.ZeroTheme
import kotlin.math.PI
import kotlin.math.sin

/** The buttons of the controller, in the order the animation presses them. */
enum class Pad { DPadRight, A, B, X, Y, LeftBumper, RightBumper, LeftStick, RightStick }

/**
 * The controller that appears when gaming mode is switched on: it drops in
 * and its buttons are pressed one after another, each with a small tick, then
 * the words come up and it fades.
 */
object GamepadTimeline {
    /** Milliseconds from the start until each button is pressed. */
    val presses: List<Pair<Pad, Int>> = listOf(
        Pad.DPadRight to 520, Pad.A to 760, Pad.B to 960, Pad.X to 1160, Pad.Y to 1360,
        Pad.LeftBumper to 1600, Pad.RightBumper to 1740, Pad.LeftStick to 1940, Pad.RightStick to 2140,
    )
    const val PRESS_MS = 170
    const val DROP_IN_MS = 420
    const val TOTAL_MS = 3300
    private const val FADE_FROM_MS = 2900

    /** How far down `pad` is at `ms`: 0 up, 1 fully pressed. It goes down in 40 ms, is held, and comes back up. */
    fun depth(pad: Pad, ms: Float): Float {
        val start = presses.first { it.first == pad }.second
        val t = ms - start
        return when {
            t < 0f || t > PRESS_MS -> 0f
            t < 40f -> t / 40f
            t < 110f -> 1f
            else -> 1f - (t - 110f) / (PRESS_MS - 110f)
        }
    }

    /** The buttons pressed on the frame at `ms` that were not pressed on the frame before. */
    fun newPresses(before: Float, now: Float): List<Pad> =
        presses.filter { (_, at) -> before < at && now >= at }.map { it.first }

    /** The whole picture's opacity: in at the start, out at the end. */
    fun opacity(ms: Float): Float = when {
        ms < 160f -> (ms / 160f).coerceIn(0f, 1f)
        ms > FADE_FROM_MS -> (1f - (ms - FADE_FROM_MS) / (TOTAL_MS - FADE_FROM_MS)).coerceIn(0f, 1f)
        else -> 1f
    }

    /** How far the controller has dropped into place, with a small overshoot: `0..~1.08`. */
    fun drop(ms: Float): Float {
        val t = (ms / DROP_IN_MS).coerceIn(0f, 1f)
        val back = 1f + 2.4f * (t - 1f) * (t - 1f) * (t - 1f) + 1.4f * (t - 1f) * (t - 1f)
        return back
    }
}

/**
 * Plays the controller once each time [playId] changes (and [playId] is not 0).
 * The overlay ignores touches: it sits over the screen without blocking it.
 */
@Composable
fun GamepadBurst(playId: Int, modifier: Modifier = Modifier) {
    if (playId == 0) return
    val reduced = LocalReducedMotion.current
        var ms by remember(playId) { mutableFloatStateOf(0f) }
    LaunchedEffect(playId, reduced) {
        if (reduced) {
            ms = 1900f
            kotlinx.coroutines.delay(1400)
            ms = GamepadTimeline.TOTAL_MS + 1f
            return@LaunchedEffect
        }
        val begin = withFrameNanos { it }
        var last = 0f
        while (last < GamepadTimeline.TOTAL_MS) {
            val frame = withFrameNanos { it }
            val now = (frame - begin) / 1_000_000f
            if (GamepadTimeline.newPresses(last, now).isNotEmpty()) {
                Unit
            }
            last = now
            ms = now
        }
    }
    if (ms > GamepadTimeline.TOTAL_MS) return
    val c = ZeroTheme.colors
    val alpha = GamepadTimeline.opacity(ms)
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.graphicsLayer { this.alpha = alpha },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Canvas(
                Modifier
                    .size(width = 280.dp, height = 178.dp)
                    .graphicsLayer {
                        val d = GamepadTimeline.drop(ms)
                        scaleX = 0.7f + 0.3f * d
                        scaleY = 0.7f + 0.3f * d
                        translationY = (1f - d) * -60.dp.toPx()
                        rotationZ = (1f - d) * -8f
                    },
            ) { drawController(ms, c.surfaceHi, c.accentHot, c.muted, c.isDark) }
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.gaming_on_title),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
                color = c.accentHot,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.gaming_on_note),
                style = MaterialTheme.typography.bodySmall,
                color = c.text,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
            )
        }
    }
}

// ---------------------------------------------------------------- drawing

private val GREEN = Color(0xFF3DDC84)
private val RED = Color(0xFFFF5A5F)
private val BLUE = Color(0xFF4DA3FF)
private val YELLOW = Color(0xFFFFC94D)

/** The controller in a 300 by 190 box, scaled to fit the canvas. */
private fun DrawScope.drawController(ms: Float, body: Color, edge: Color, detail: Color, dark: Boolean) {
    val k = minOf(size.width / 300f, size.height / 190f)
    withTransform({
        translate((size.width - 300f * k) / 2f, (size.height - 190f * k) / 2f)
        scale(k, k, pivot = Offset.Zero)
    }) {
        fun down(pad: Pad) = GamepadTimeline.depth(pad, ms)

        // Bumpers sit behind the body and dip when pressed.
        for ((pad, x) in listOf(Pad.LeftBumper to 58f, Pad.RightBumper to 192f)) {
            val d = down(pad)
            val top = 26f + 4f * d
            drawRoundRect(if (d > 0f) edge else detail.copy(alpha = 0.55f), Offset(x, top), Size(50f, 12f), CornerRadius(6f))
        }

        // The body: a wide middle and two grips, as one shape.
        val middle = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(38f, 36f, 262f, 132f), CornerRadius(46f))) }
        val leftGrip = Path().apply { addOval(Rect(28f, 82f, 108f, 182f)) }
        val rightGrip = Path().apply { addOval(Rect(192f, 82f, 272f, 182f)) }
        val shape = Path.combine(PathOperation.Union, Path.combine(PathOperation.Union, middle, leftGrip), rightGrip)
        drawPath(shape, body)
        drawPath(shape, edge.copy(alpha = 0.85f), style = Stroke(3f))
        // A soft highlight along the top.
        drawRoundRect(Color.White.copy(alpha = if (dark) 0.06f else 0.25f), Offset(56f, 42f), Size(188f, 10f), CornerRadius(5f))

        // Left stick.
        val ls = down(Pad.LeftStick)
        val lsShift = Offset(6f * sin(ls * PI.toFloat()), -4f * ls)
        drawCircle(detail.copy(alpha = 0.35f), 21f, Offset(96f, 78f))
        drawCircle(if (ls > 0f) edge else detail, 15f, Offset(96f, 78f) + lsShift)
        drawCircle(Color.Black.copy(alpha = 0.25f), 9f, Offset(96f, 78f) + lsShift)

        // Right stick.
        val rs = down(Pad.RightStick)
        val rsShift = Offset(-6f * sin(rs * PI.toFloat()), 4f * rs)
        drawCircle(detail.copy(alpha = 0.35f), 19f, Offset(190f, 118f))
        drawCircle(if (rs > 0f) edge else detail, 13f, Offset(190f, 118f) + rsShift)
        drawCircle(Color.Black.copy(alpha = 0.25f), 8f, Offset(190f, 118f) + rsShift)

        // D-pad.
        val pad = Offset(122f, 118f)
        val right = down(Pad.DPadRight)
        drawRoundRect(detail, Offset(pad.x - 14f, pad.y - 5f), Size(28f, 10f), CornerRadius(3f))
        drawRoundRect(detail, Offset(pad.x - 5f, pad.y - 14f), Size(10f, 28f), CornerRadius(3f))
        if (right > 0f) drawRoundRect(edge, Offset(pad.x + 3f, pad.y - 5f), Size(11f, 10f), CornerRadius(3f))

        // Face buttons.
        val face = Offset(222f, 78f)
        fun button(which: Pad, at: Offset, color: Color) {
            val d = down(which)
            if (d > 0f) {
                // The ring of light a press leaves.
                val t = 1f - d
                drawCircle(color.copy(alpha = 0.35f * d), 15f + 9f * t, at)
            }
            drawCircle(Color.Black.copy(alpha = 0.3f), 10f, at + Offset(0f, 2f))
            drawCircle(color.copy(alpha = if (d > 0f) 1f else 0.85f), 10f * (1f - 0.12f * d), at + Offset(0f, 2f * d))
            drawCircle(Color.White.copy(alpha = 0.35f), 3f, at + Offset(-3f, -3f + 2f * d))
        }
        button(Pad.Y, face + Offset(0f, -19f), YELLOW)
        button(Pad.B, face + Offset(19f, 0f), RED)
        button(Pad.A, face + Offset(0f, 19f), GREEN)
        button(Pad.X, face + Offset(-19f, 0f), BLUE)

        // View, menu and the middle button.
        drawCircle(detail.copy(alpha = 0.8f), 4.5f, Offset(138f, 70f))
        drawCircle(detail.copy(alpha = 0.8f), 4.5f, Offset(162f, 70f))
        drawCircle(edge.copy(alpha = 0.9f), 7f, Offset(150f, 54f))
        drawCircle(body, 3.5f, Offset(150f, 54f))
    }
}
