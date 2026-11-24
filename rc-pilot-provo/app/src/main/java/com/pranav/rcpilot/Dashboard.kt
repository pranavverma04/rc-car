package com.pranav.rcpilot

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Car interior palette: black and graphite cabin, white instruments, one red needle, amber warning lights. */
object Dash {
    val Roof = Color(0xFF16171A)
    val Cabin = Color(0xFF0E0F11)
    val Floor = Color(0xFF070708)
    val Panel = Color(0xFF1C1D21)
    val Bezel = Color(0xFF3A3C42)
    val White = Color(0xFFF2F2F0)
    val Grey = Color(0xFF8C8F96)
    val Red = Color(0xFFE53935)
    val Amber = Color(0xFFFFB020)
    val Green = Color(0xFF3DDC84)
}

private const val MAX_TURN = 150f   // degrees of wheel rotation for full lock

/**
 * Steering wheel: put a finger on it and turn it round like a real wheel. Springs back to straight on release.
 * Reports -1 (full left) .. 1 (full right).
 */
@Composable
fun SteeringWheel(modifier: Modifier = Modifier, onSteer: (Float) -> Unit) {
    val angle = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Canvas(
        modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val c = Offset(size.width / 2f, size.height / 2f)
                fun deg(p: Offset) = Math.toDegrees(atan2((p.y - c.y).toDouble(), (p.x - c.x).toDouble())).toFloat()
                var last = deg(down.position)
                var a = angle.value
                scope.launch { angle.stop() }
                drag(down.id) { ch ->
                    val now = deg(ch.position)
                    var d = now - last
                    if (d > 180f) d -= 360f
                    if (d < -180f) d += 360f
                    last = now
                    a = (a + d).coerceIn(-MAX_TURN, MAX_TURN)
                    scope.launch { angle.snapTo(a) }
                    onSteer(a / MAX_TURN)
                    ch.consume()
                }
                onSteer(0f)
                scope.launch { angle.animateTo(0f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)) }
            }
        }
    ) {
        val c = center
        val r = min(size.width, size.height) / 2f * 0.96f
        val rim = r * 0.16f
        rotate(angle.value, c) {
            // Rim: thick leather ring with a soft top light and stitching
            drawCircle(Color(0xFF0A0A0B), r - rim / 2, c, style = Stroke(rim + 6f))
            drawCircle(Brush.sweepGradient(listOf(Color(0xFF2E2F33), Color(0xFF1A1B1E), Color(0xFF2E2F33), Color(0xFF1A1B1E), Color(0xFF2E2F33)), c),
                r - rim / 2, c, style = Stroke(rim))
            drawCircle(Color.White.copy(alpha = 0.05f), r - rim * 0.25f, c, style = Stroke(rim * 0.3f))
            val dash = PathEffect.dashPathEffect(floatArrayOf(7f, 6f))
            drawCircle(Color(0xFF55575D), r - rim * 0.18f, c, style = Stroke(1.5f, pathEffect = dash))
            drawCircle(Color(0xFF55575D), r - rim * 0.82f, c, style = Stroke(1.5f, pathEffect = dash))
            // Centre marker at 12 o'clock so you can see how far the wheel is turned
            drawArc(Dash.Red, -96f, 12f, false, Offset(c.x - (r - rim / 2), c.y - (r - rim / 2)),
                Size((r - rim / 2) * 2, (r - rim / 2) * 2), style = Stroke(rim))
            // Three spokes: left, right, bottom
            val hubR = r * 0.30f
            for (deg in listOf(180f, 0f, 90f)) {
                val rad = deg * PI.toFloat() / 180f
                val dir = Offset(cos(rad), sin(rad))
                val w = if (deg == 90f) r * 0.20f else r * 0.26f
                drawLine(Brush.linearGradient(listOf(Color(0xFF4A4C52), Color(0xFF26272B)), c - Offset(0f, w), c + Offset(0f, w)),
                    c + dir * (hubR * 0.6f), c + dir * (r - rim * 0.9f), strokeWidth = w, cap = StrokeCap.Round)
            }
            // Hub with a brushed metal badge
            drawCircle(Brush.radialGradient(listOf(Color(0xFF2B2C30), Color(0xFF121315)), c, hubR), hubR, c)
            drawCircle(Dash.Bezel, hubR, c, style = Stroke(2f))
            drawCircle(Brush.linearGradient(listOf(Color(0xFFD9DADC), Color(0xFF7D8086)), c - Offset(hubR, hubR) * 0.35f, c + Offset(hubR, hubR) * 0.35f),
                hubR * 0.32f, c)
            drawCircle(Color(0xFF16171A), hubR * 0.32f, c, style = Stroke(2f))
        }
    }
}

/** Speedometer: motor power 0..100, red needle, gear letter in the middle. */
@Composable
fun Speedometer(power: Int, reverse: Boolean, braking: Boolean, modifier: Modifier = Modifier) {
    val needle = remember { Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(power) { needle.animateTo(power.toFloat(), spring(stiffness = Spring.StiffnessMedium)) }
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val r = min(size.width, size.height) / 2f
            val start = 135f
            val sweep = 270f
            // Bezel and face
            drawCircle(Brush.linearGradient(listOf(Color(0xFF5A5C62), Color(0xFF1E1F23)), Offset(0f, 0f), Offset(size.width, size.height)), r, c)
            drawCircle(Brush.radialGradient(listOf(Color(0xFF15161A), Color(0xFF050506)), c, r), r * 0.92f, c)
            // Red zone above 80
            drawArc(Dash.Red.copy(alpha = 0.8f), start + sweep * 0.8f, sweep * 0.2f, false,
                Offset(c.x - r * 0.80f, c.y - r * 0.80f), Size(r * 1.6f, r * 1.6f), style = Stroke(r * 0.05f))
            // Ticks every 5, long every 20
            for (i in 0..20) {
                val v = i * 5
                val a = (start + sweep * v / 100f) * PI.toFloat() / 180f
                val dir = Offset(cos(a), sin(a))
                val long = v % 20 == 0
                val inner = if (long) r * 0.70f else r * 0.76f
                drawLine(if (v >= 80) Dash.Red else Dash.White, c + dir * inner, c + dir * (r * 0.84f),
                    strokeWidth = if (long) 4f else 2f, cap = StrokeCap.Round)
            }
            // Needle
            val a = (start + sweep * needle.value.coerceIn(0f, 100f) / 100f) * PI.toFloat() / 180f
            val dir = Offset(cos(a), sin(a))
            drawLine(Dash.Red.copy(alpha = 0.3f), c - dir * r * 0.12f, c + dir * r * 0.80f, strokeWidth = 10f, cap = StrokeCap.Round)
            drawLine(Dash.Red, c - dir * r * 0.12f, c + dir * r * 0.80f, strokeWidth = 4f, cap = StrokeCap.Round)
            drawCircle(Color(0xFF2A2B2F), r * 0.09f, c)
            drawCircle(Dash.Bezel, r * 0.09f, c, style = Stroke(2f))
        }
        // Numbers 0..100
        for (v in 0..100 step 20) {
            val a = (135f + 270f * v / 100f) * PI.toFloat() / 180f
            Text("$v", color = if (v >= 80) Dash.Red else Dash.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.offset(x = (cos(a) * 58).dp, y = (sin(a) * 58).dp))
        }
        Column(Modifier.offset(y = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$power", color = Dash.White, fontSize = 26.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
            Text("% POWER", color = Dash.Grey, fontSize = 8.sp, letterSpacing = 1.sp)
        }
        // Gear and brake lamps
        Text(if (braking) "BRAKE" else if (reverse) "R" else "D",
            color = if (braking) Dash.Red else if (reverse) Dash.Amber else Dash.Green,
            fontSize = if (braking) 12.sp else 20.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp,
            modifier = Modifier.offset(y = 80.dp))
    }
}

/**
 * Gear lever in a gate: D at the top, R at the bottom. Drag the knob or tap a letter.
 * Reports true for R.
 */
@Composable
fun GearLever(reverse: Boolean, modifier: Modifier = Modifier, onGear: (Boolean) -> Unit) {
    var dragY by remember { mutableStateOf<Float?>(null) }
    Box(
        modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                dragY = down.position.y
                drag(down.id) { ch -> dragY = ch.position.y; ch.consume() }
                val y = dragY ?: down.position.y
                onGear(y > size.height / 2f)   // let go in the lower half = R
                dragY = null
            }
        }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            // Console plate
            drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF26272B), Color(0xFF141518))), cornerRadius = CornerRadius(18f))
            drawRoundRect(Dash.Bezel, cornerRadius = CornerRadius(18f), style = Stroke(2f))
            // Gate slot
            val slotW = w * 0.16f
            val top = h * 0.16f
            val bottom = h * 0.84f
            val sx = w * 0.38f   // slot sits left of centre so the D / R letters have room on the right
            drawRoundRect(Color.Black, Offset(sx - slotW / 2, top), Size(slotW, bottom - top), CornerRadius(slotW / 2))
            // Knob: follows the finger while dragging, otherwise sits on the selected gear
            val ky = (dragY ?: if (reverse) bottom else top).coerceIn(top, bottom)
            val kr = w * 0.26f
            drawLine(Color(0xFF4A4C52), Offset(sx, ky), Offset(sx, ky + kr * 0.4f), strokeWidth = slotW * 0.8f)
            drawCircle(Color.Black.copy(alpha = 0.5f), kr, Offset(sx + 3f, ky + 5f))
            drawCircle(Brush.radialGradient(listOf(Color(0xFF55575D), Color(0xFF1B1C1F)), Offset(sx - kr * 0.3f, ky - kr * 0.3f), kr * 1.4f),
                kr, Offset(sx, ky))
            drawCircle(Color(0xFF6A6D73), kr, Offset(sx, ky), style = Stroke(1.5f))
        }
        Text("D", color = if (!reverse) Dash.Green else Dash.Grey, fontSize = 22.sp, fontWeight = FontWeight.Black,
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 14.dp, top = 14.dp))
        Text("R", color = if (reverse) Dash.Amber else Dash.Grey, fontSize = 22.sp, fontWeight = FontWeight.Black,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = 14.dp))
    }
}

/** Gas pedal: where your thumb sits sets the power. Top edge = gentle, slide down = more, bottom = full. */
@Composable
fun GasPedal(modifier: Modifier = Modifier, onGas: (Float) -> Unit) {
    var depth by remember { mutableFloatStateOf(0f) }
    var pressed by remember { mutableStateOf(false) }
    Canvas(
        modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                fun at(y: Float) = (0.15f + 0.85f * (y / size.height)).coerceIn(0.15f, 1f)
                pressed = true
                depth = at(down.position.y); onGas(depth)
                drag(down.id) { ch -> depth = at(ch.position.y); onGas(depth); ch.consume() }
                pressed = false
                depth = 0f; onGas(0f)
            }
        }
    ) {
        drawPedal(depth, pressed, ribs = 9, label = Dash.Green)
    }
}

@Composable
fun PedalLabel(text: String, color: Color) {
    Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp,
        modifier = Modifier.padding(top = 6.dp))
}

/** Brake pedal: hold to stop. */
@Composable
fun BrakePedal(modifier: Modifier = Modifier, onBrake: (Boolean) -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    Canvas(
        modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown()
                pressed = true; onBrake(true)
                waitForUpOrCancellation()
                pressed = false; onBrake(false)
            }
        }
    ) {
        drawPedal(if (pressed) 1f else 0f, pressed, ribs = 5, label = Dash.Red)
    }
}

/** Metal pedal with rubber ribs. Pressing shrinks it a little (it tips away from you) and lights the edge. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPedal(depth: Float, pressed: Boolean, ribs: Int, label: Color) {
    val squash = 1f - 0.10f * depth
    val w = size.width * squash
    val h = size.height * squash
    val o = Offset((size.width - w) / 2, size.height - h)
    // Shadow, metal plate, rubber face
    drawRoundRect(Color.Black.copy(alpha = 0.5f), o + Offset(4f, 8f), Size(w, h), CornerRadius(22f))
    drawRoundRect(Brush.linearGradient(listOf(Color(0xFF9A9DA3), Color(0xFF4E5157), Color(0xFF8C8F95)), o, o + Offset(w, h)),
        o, Size(w, h), CornerRadius(22f))
    val inset = w * 0.10f
    val fo = o + Offset(inset, inset)
    val fs = Size(w - inset * 2, h - inset * 2)
    drawRoundRect(Color(0xFF17181B), fo, fs, CornerRadius(14f))
    for (i in 1..ribs) {
        val y = fo.y + fs.height * i / (ribs + 1)
        drawLine(Color(0xFF2C2D31), Offset(fo.x + fs.width * 0.12f, y), Offset(fo.x + fs.width * 0.88f, y), strokeWidth = 5f, cap = StrokeCap.Round)
    }
    // Depth fill on the rubber (gas) or a full glow (brake)
    if (pressed) {
        drawRoundRect(label.copy(alpha = 0.18f), Offset(fo.x, fo.y + fs.height * (1f - depth)), Size(fs.width, fs.height * depth), CornerRadius(14f))
        drawRoundRect(label, o, Size(w, h), CornerRadius(22f), style = Stroke(3f))
    }
}
