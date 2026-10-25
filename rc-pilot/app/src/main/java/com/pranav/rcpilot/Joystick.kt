package com.pranav.rcpilot

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.min

enum class Axis { X, Y, BOTH }

/**
 * Thumb stick that springs back to the centre on release.
 * Reports x (right +) and y (up +), each -1..1. Each stick tracks its own finger, so two work at once.
 */
@Composable
fun Joystick(axis: Axis, accent: Color, enabled: Boolean, modifier: Modifier = Modifier, onMove: (Float, Float) -> Unit) {
    var knob by remember { mutableStateOf(Offset.Zero) }
    Canvas(
        modifier.pointerInput(axis, enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown()
                val c = Offset(size.width / 2f, size.height / 2f)
                val travel = min(size.width, size.height) / 2f * 0.62f
                fun update(p: Offset) {
                    var d = (p - c) / travel
                    d = Offset(if (axis == Axis.Y) 0f else d.x, if (axis == Axis.X) 0f else d.y)
                    val len = d.getDistance()
                    if (len > 1f) d /= len
                    knob = d
                    onMove(d.x, -d.y)
                }
                update(down.position)
                drag(down.id) { change ->
                    update(change.position)
                    change.consume()
                }
                knob = Offset.Zero
                onMove(0f, 0f)
            }
        }
    ) {
        val c = center
        val r = min(size.width, size.height) / 2f
        val travel = r * 0.62f
        val dim = if (enabled) 1f else 0.35f
        drawCircle(Color.White.copy(alpha = 0.04f * dim), r * 0.95f, c)
        drawCircle(Color.White.copy(alpha = 0.12f * dim), r * 0.95f, c, style = Stroke(2f))
        // Slot showing which way this stick moves
        val slotW = r * 0.22f
        when (axis) {
            Axis.Y -> drawRoundRect(Color.White.copy(alpha = 0.07f * dim), Offset(c.x - slotW / 2, c.y - travel - slotW / 2),
                Size(slotW, travel * 2 + slotW), CornerRadius(slotW / 2))
            Axis.X -> drawRoundRect(Color.White.copy(alpha = 0.07f * dim), Offset(c.x - travel - slotW / 2, c.y - slotW / 2),
                Size(travel * 2 + slotW, slotW), CornerRadius(slotW / 2))
            Axis.BOTH -> drawCircle(Color.White.copy(alpha = 0.07f * dim), travel, c)
        }
        val k = c + knob * travel
        val active = knob != Offset.Zero
        drawCircle(accent.copy(alpha = (if (active) 0.25f else 0.10f) * dim), r * 0.42f, k)
        drawCircle(accent.copy(alpha = dim), r * 0.30f, k)
        drawCircle(Color.Black.copy(alpha = 0.25f), r * 0.30f, k, style = Stroke(3f))
    }
}
