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
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

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
        val dim = if (enabled) 1f else 0.4f
        // Recessed well with a ring of tick marks around it
        drawCircle(Color(0xFF0F0E0D), r * 0.92f, c)
        drawCircle(Color(0xFF34312C), r * 0.92f, c, style = Stroke(r * 0.03f))
        for (i in 0 until 24) {
            val a = i * Math.PI / 12
            val long = i % 6 == 0
            val r0 = r * (if (long) 0.95f else 0.97f)
            drawLine(Color(0xFFEDE6D6).copy(alpha = (if (long) 0.6f else 0.22f) * dim),
                c + Offset((r0 * cos(a)).toFloat(), (r0 * sin(a)).toFloat()),
                c + Offset((r * cos(a)).toFloat(), (r * sin(a)).toFloat()), if (long) 3f else 2f)
        }
        // Slot showing which way this stick moves
        val slotW = r * 0.2f
        val slot = Color(0xFF252320)
        when (axis) {
            Axis.Y -> drawRoundRect(slot, Offset(c.x - slotW / 2, c.y - travel - slotW / 2), Size(slotW, travel * 2 + slotW), CornerRadius(slotW / 2))
            Axis.X -> drawRoundRect(slot, Offset(c.x - travel - slotW / 2, c.y - slotW / 2), Size(travel * 2 + slotW, slotW), CornerRadius(slotW / 2))
            Axis.BOTH -> drawCircle(slot, travel, c)
        }
        // Rubber thumb cap: drop shadow, body, grip rings, small orange dot in the middle
        val k = c + knob * travel
        val cap = r * 0.32f
        drawCircle(Color.Black.copy(alpha = 0.5f), cap * 1.04f, k + Offset(0f, cap * 0.12f))
        drawCircle(Color(0xFF3A3733).copy(alpha = dim), cap, k)
        for (ring in 1..3) drawCircle(Color(0xFF26241F), cap * (1f - ring * 0.2f), k, style = Stroke(cap * 0.06f))
        drawCircle(Color.White.copy(alpha = 0.06f), cap, k + Offset(-cap * 0.08f, -cap * 0.1f), style = Stroke(cap * 0.05f))
        drawCircle(accent.copy(alpha = dim), cap * 0.14f, k)
    }
}
