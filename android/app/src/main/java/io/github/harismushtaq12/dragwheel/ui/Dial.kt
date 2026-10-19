package io.github.harismushtaq12.dragwheel.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import io.github.harismushtaq12.dragwheel.core.Mode
import io.github.harismushtaq12.dragwheel.core.WheelAction
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private const val KNOB = -2
private const val NONE = -1
private const val INNER = 0.38f   // inner radius as a share of the outer radius
private const val KNOB_R = 0.32f
private const val GAP_DEG = 1.6f

/**
 * The wheel. Tap a slice, or press the knob, slide to a slice and let go.
 * Tapping the knob calls [onKnobTap].
 */
@Composable
fun Dial(
    actions: List<WheelAction>,
    mode: Mode,
    working: Boolean,
    knobTop: String,
    knobMain: String,
    onPick: (Int) -> Unit,
    onKnobTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val haptic = LocalHapticFeedback.current
    var hot by remember { mutableIntStateOf(NONE) }
    val currentActions by rememberUpdatedState(actions)
    val currentWorking by rememberUpdatedState(working)
    val pick by rememberUpdatedState(onPick)
    val knobTap by rememberUpdatedState(onKnobTap)

    // Only animate while working, so an idle wheel draws nothing new.
    val angle = if (working) {
        val spin = rememberInfiniteTransition(label = "spin")
        spin.animateFloat(
            initialValue = 0f, targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
            label = "angle",
        ).value
    } else 0f

    Canvas(
        modifier
            .aspectRatio(1f)
            .semantics { contentDescription = "Action wheel" }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (currentWorking) return@awaitEachGesture
                    fun zoneAt(p: Offset): Int {
                        val c = Offset(size.width / 2f, size.height / 2f)
                        val outer = min(size.width, size.height) / 2f
                        val d = (p - c).getDistance()
                        if (d < outer * INNER) return KNOB
                        val n = currentActions.size
                        if (d > outer * 1.08f || n == 0) return NONE
                        val span = 360f / n
                        val deg = Math.toDegrees(atan2((p.y - c.y).toDouble(), (p.x - c.x).toDouble())).toFloat()
                        val fromTop = (((deg + 90f + span / 2f) % 360f) + 360f) % 360f
                        return min(n - 1, (fromTop / span).toInt())
                    }

                    val start = zoneAt(down.position)
                    var current = start
                    hot = if (start >= 0) start else NONE
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val z = zoneAt(change.position)
                        if (z != current) {
                            current = z
                            hot = if (z >= 0) z else NONE
                            if (z >= 0) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        change.consume()
                    }
                    hot = NONE
                    when {
                        current >= 0 -> pick(current)
                        current == KNOB && start == KNOB -> knobTap()
                    }
                }
            }
    ) {
        val c = center
        val outer = size.minDimension / 2f
        val inner = outer * INNER
        val n = actions.size

        drawCircle(
            when (mode) { Mode.CONVERT -> Palette.Ring; Mode.EDIT -> Palette.RingEdit; Mode.TOOLS -> Palette.RingTools },
            radius = outer, center = c,
        )

        val idle = when (mode) { Mode.CONVERT -> Palette.Segment; Mode.EDIT -> Palette.SegmentEdit; Mode.TOOLS -> Palette.SegmentTools }
        val slices = if (n == 0) 6 else n
        val span = 360f / slices
        val titleStyle = TextStyle(
            fontSize = when { slices > 9 -> 12.sp; slices > 6 -> 14.sp; else -> 17.sp },
            fontWeight = FontWeight.SemiBold,
        )
        val hintStyle = TextStyle(fontSize = if (slices > 8) 9.sp else 11.sp)
        val showHints = slices <= 10
        val labelR = (outer + inner) / 2f + outer * 0.02f
        // Labels must fit their slice: narrower when there are many slices.
        val labelWidth = minOf(outer * 0.62f, (2f * Math.PI.toFloat() * labelR / slices) * 0.95f).toInt().coerceAtLeast(1)

        for (i in 0 until slices) {
            val mid = -90f + i * span
            val isHot = i == hot && n > 0
            val fill = when {
                n == 0 -> idle.copy(alpha = 0.5f)
                working -> idle.copy(alpha = 0.55f)
                isHot -> Palette.Mint
                else -> idle
            }
            drawPath(wedge(c, inner + outer * 0.02f, outer * 0.98f, mid - span / 2 + GAP_DEG / 2, span - GAP_DEG), fill)

            if (n > 0) {
                val a = actions[i]
                val rad = Math.toRadians(mid.toDouble())
                val x = c.x + labelR * cos(rad).toFloat()
                val y = c.y + labelR * sin(rad).toFloat()
                val textColor = if (isHot) Palette.OnMint else Palette.Text
                val hintColor = if (isHot) Palette.OnMint else Palette.Muted
                val t = measurer.measure(a.title, titleStyle.copy(color = textColor), maxLines = 1,
                    overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = labelWidth))
                val h = measurer.measure(if (showHints) a.hint else "", hintStyle.copy(color = hintColor), maxLines = 1,
                    overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = labelWidth))
                val total = t.size.height + h.size.height
                drawText(t, topLeft = Offset(x - t.size.width / 2f, y - total / 2f))
                drawText(h, topLeft = Offset(x - h.size.width / 2f, y - total / 2f + t.size.height))
            }
        }

        // Knob
        val knobR = outer * KNOB_R
        drawCircle(Palette.Knob, radius = knobR, center = c)
        if (working) {
            drawArc(
                color = Palette.Mint, startAngle = angle, sweepAngle = 90f, useCenter = false,
                topLeft = Offset(c.x - knobR - 6f, c.y - knobR - 6f),
                size = androidx.compose.ui.geometry.Size((knobR + 6f) * 2, (knobR + 6f) * 2),
                style = Stroke(width = 8f),
            )
        }
        val hotTitle = if (hot >= 0 && hot < n) actions[hot].title else knobMain
        drawKnobText(measurer, c, knobR, knobTop, hotTitle)
    }
}

private fun wedge(c: Offset, r0: Float, r1: Float, start: Float, sweep: Float): Path = Path().apply {
    arcTo(Rect(c, r1), start, sweep, forceMoveTo = true)
    arcTo(Rect(c, r0), start + sweep, -sweep, forceMoveTo = false)
    close()
}

private fun DrawScope.drawKnobText(
    measurer: androidx.compose.ui.text.TextMeasurer,
    c: Offset,
    knobR: Float,
    top: String,
    main: String,
) {
    val width = (knobR * 1.7f).toInt().coerceAtLeast(1)
    val topLayout = measurer.measure(top, TextStyle(fontSize = 12.sp, color = Palette.KnobMuted), maxLines = 1,
        overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = width))
    val mainLayout = measurer.measure(main, TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Palette.KnobText),
        maxLines = 1, overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = width))
    val total = (if (top.isEmpty()) 0 else topLayout.size.height) + mainLayout.size.height
    var y = c.y - total / 2f
    if (top.isNotEmpty()) {
        drawText(topLayout, topLeft = Offset(c.x - topLayout.size.width / 2f, y))
        y += topLayout.size.height
    }
    drawText(mainLayout, topLeft = Offset(c.x - mainLayout.size.width / 2f, y))
}
