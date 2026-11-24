package com.pranav.rcpilot

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Cabin palette: grey plastic dash, black instrument panel, white dials, one red needle. */
object Dash {
    val Plastic = Color(0xFFB4B4B6)
    val PlasticDark = Color(0xFF8E8E90)
    val Panel = Color(0xFF0B0B0C)
    val Bezel = Color(0xFF3A3C42)
    val White = Color(0xFFF2F2F0)
    val Grey = Color(0xFF8C8F96)
    val Red = Color(0xFFE53935)
    val Orange = Color(0xFFE2521D)
    val Amber = Color(0xFFFFB020)
    val Green = Color(0xFF3DDC84)
}

/** Where the dashboard starts, as a share of the screen height. */
const val DASH_TOP = 0.695f

private const val MAX_TURN = 150f   // degrees of wheel rotation for full lock

private fun textPaint(bold: Boolean = true) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    textAlign = Paint.Align.CENTER
    typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
}

private fun DrawScope.label(p: Paint, text: String, x: Float, y: Float, sizePx: Float, c: Color) {
    p.textSize = sizePx
    p.color = c.toArgb()
    drawContext.canvas.nativeCanvas.drawText(text, x, y + sizePx * 0.36f, p)
}

/** The grey dash across the bottom with the black instrument panel set into it. */
@Composable
fun DashboardBody(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val top = h * DASH_TOP
        drawRect(Brush.verticalGradient(listOf(Dash.Plastic, Color(0xFFD0D0D2), Dash.PlasticDark), top, h), Offset(0f, top), Size(w, h - top))
        drawRect(Color(0xFF55565A), Offset(0f, top - 2f), Size(w, 5f))

        // Instrument panel
        val px = w * 0.232f
        val pw = w * 0.552f
        val py = top + h * 0.012f
        drawRoundRect(Color(0xFF6B6C70), Offset(px - 5f, py - 5f), Size(pw + 10f, h), CornerRadius(26f))
        drawRoundRect(Dash.Panel, Offset(px, py), Size(pw, h), CornerRadius(22f))

        // Vents to the right of the wheel
        val vx = w * 0.925f
        val vy = h * 0.80f
        for (i in 0..7) {
            val x = vx + i * w * 0.011f
            drawLine(Color(0xFF5F6063), Offset(x, vy), Offset(x, h), strokeWidth = 4f)
        }
    }
}

/** Steering wheel: put a finger on it and turn it. Springs back to straight on release. Reports -1 (left) .. 1 (right). */
@Composable
fun SteeringWheel(modifier: Modifier = Modifier, onSteer: (Float) -> Unit) {
    val angle = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val rimDark = Color(0xFF26282B)
    val rimLight = Color(0xFF52555A)
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
                scope.launch { angle.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow)) }
            }
        }
    ) {
        val c = center
        val r = min(size.width, size.height) / 2f * 0.97f
        val rim = r * 0.115f
        rotate(angle.value, c) {
            // Spokes: left, right, and a wide lower one
            val spoke = Brush.verticalGradient(listOf(Color(0xFF5B5E63), Color(0xFF34363A)), c.y - r * 0.2f, c.y + r * 0.2f)
            drawLine(spoke, c + Offset(-r * 0.95f, -r * 0.03f), c + Offset(r * 0.95f, -r * 0.03f), strokeWidth = r * 0.17f)
            drawLine(Color(0xFF34363A), c, c + Offset(-r * 0.32f, r * 0.82f), strokeWidth = r * 0.17f)
            drawLine(Color(0xFF34363A), c, c + Offset(r * 0.32f, r * 0.82f), strokeWidth = r * 0.17f)
            // Rim
            drawCircle(Color(0xFF111214), r - rim / 2, c, style = Stroke(rim + 5f))
            drawCircle(
                Brush.verticalGradient(listOf(rimLight, rimDark, rimLight), c.y - r, c.y + r),
                r - rim / 2, c, style = Stroke(rim)
            )
            // Hub: rounded pad
            val hw = r * 0.88f
            val hh = r * 0.62f
            val hub = Rect(c.x - hw / 2, c.y - hh * 0.46f, c.x + hw / 2, c.y + hh * 0.54f)
            drawRoundRect(Color(0xFF151618), hub.topLeft + Offset(0f, 4f), hub.size, CornerRadius(r * 0.20f))
            drawRoundRect(
                Brush.verticalGradient(listOf(Color(0xFF55585D), Color(0xFF2E3034)), hub.top, hub.bottom),
                hub.topLeft, hub.size, CornerRadius(r * 0.20f)
            )
            val inner = Rect(c.x - hw * 0.28f, c.y + hh * 0.08f, c.x + hw * 0.28f, c.y + hh * 0.42f)
            drawRoundRect(Color(0xFF6A6D72), inner.topLeft, inner.size, CornerRadius(r * 0.07f))
        }
    }
}

/** Speedometer: real motor power 0..100 % with a red needle. */
@Composable
fun Speedometer(power: Int, modifier: Modifier = Modifier) {
    val needle = remember { Animatable(0f) }
    val paint = remember { textPaint() }
    LaunchedEffect(power) { needle.animateTo(power.toFloat(), spring(stiffness = Spring.StiffnessMedium)) }
    Canvas(modifier) {
        val c = center
        val r = min(size.width, size.height) / 2f
        val start = 130f
        val sweep = 280f
        drawCircle(Color(0xFF1C1D20), r, c)
        drawCircle(Color(0xFF050506), r * 0.96f, c)
        for (i in 0..20) {
            val v = i * 5
            val a = (start + sweep * v / 100f) * PI.toFloat() / 180f
            val dir = Offset(cos(a), sin(a))
            val long = v % 20 == 0
            drawLine(Dash.White, c + dir * (if (long) r * 0.80f else r * 0.86f), c + dir * (r * 0.92f),
                strokeWidth = if (long) r * 0.03f else r * 0.015f)
        }
        for (v in 0..100 step 20) {
            val a = (start + sweep * v / 100f) * PI.toFloat() / 180f
            label(paint, "$v", c.x + cos(a) * r * 0.62f, c.y + sin(a) * r * 0.62f, r * 0.19f, Dash.White)
        }
        label(paint, "POWER %", c.x, c.y + r * 0.66f, r * 0.13f, Dash.Grey)
        val a = (start + sweep * needle.value.coerceIn(0f, 100f) / 100f) * PI.toFloat() / 180f
        val dir = Offset(cos(a), sin(a))
        val side = Offset(-dir.y, dir.x)
        val tip = Path().apply {
            moveTo(c.x + dir.x * r * 0.84f, c.y + dir.y * r * 0.84f)
            lineTo(c.x + side.x * r * 0.035f - dir.x * r * 0.04f, c.y + side.y * r * 0.035f - dir.y * r * 0.04f)
            lineTo(c.x - side.x * r * 0.035f - dir.x * r * 0.04f, c.y - side.y * r * 0.035f - dir.y * r * 0.04f)
            close()
        }
        drawPath(tip, Dash.Red)
    }
}

/**
 * Gauge in the fuel gauge's place. The car has no battery sensor, so this shows Wi-Fi signal instead:
 * E = about to lose the car, F = strong link.
 */
@Composable
fun SignalGauge(rssi: Int, modifier: Modifier = Modifier) {
    val level by animateFloatAsState(if (rssi == 0) 0f else ((rssi + 85) / 40f).coerceIn(0f, 1f), label = "signal")
    val paint = remember { textPaint() }
    Canvas(modifier) {
        val c = center
        val r = min(size.width, size.height) / 2f
        drawCircle(Color(0xFF1C1D20), r, c)
        drawCircle(Color(0xFF050506), r * 0.96f, c)
        val low = 62f     // E, lower right
        val high = -62f   // F, upper right
        for (i in 0..4) {
            val a = (low + (high - low) * i / 4f) * PI.toFloat() / 180f
            val dir = Offset(cos(a), sin(a))
            drawLine(if (i == 0) Dash.Red else Dash.White, c + dir * (r * 0.76f), c + dir * (r * 0.92f), strokeWidth = r * 0.035f)
        }
        // Letters sit just past the ends of the scale so the needle never covers them
        val eA = 80f * PI.toFloat() / 180f
        val fA = -80f * PI.toFloat() / 180f
        label(paint, "F", c.x + cos(fA) * r * 0.66f, c.y + sin(fA) * r * 0.66f, r * 0.26f, Dash.White)
        label(paint, "E", c.x + cos(eA) * r * 0.66f, c.y + sin(eA) * r * 0.66f, r * 0.26f, Dash.White)

        // Wi-Fi mark where the fuel pump would sit
        val ic = c + Offset(-r * 0.36f, r * 0.56f)
        for (i in 1..3) {
            val rr = r * 0.075f * i
            drawArc(Dash.White, 225f, 90f, false, Offset(ic.x - rr, ic.y - rr), Size(rr * 2, rr * 2), style = Stroke(r * 0.03f, cap = StrokeCap.Round))
        }
        drawCircle(Dash.White, r * 0.028f, ic)

        val a = (low + (high - low) * level) * PI.toFloat() / 180f
        val dir = Offset(cos(a), sin(a))
        drawLine(Dash.Red, c - dir * r * 0.05f, c + dir * r * 0.72f, strokeWidth = r * 0.045f, cap = StrokeCap.Round)
    }
}

/** Indicator arrow. direction -1 points left, 1 points right. */
@Composable
fun TurnLamp(direction: Int, lit: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2
        val cy = h / 2
        val d = direction.toFloat()
        val arrow = Path().apply {
            moveTo(cx + d * w * 0.46f, cy)
            lineTo(cx + d * w * 0.02f, cy - h * 0.40f)
            lineTo(cx + d * w * 0.02f, cy - h * 0.17f)
            lineTo(cx - d * w * 0.46f, cy - h * 0.17f)
            lineTo(cx - d * w * 0.46f, cy + h * 0.17f)
            lineTo(cx + d * w * 0.02f, cy + h * 0.17f)
            lineTo(cx + d * w * 0.02f, cy + h * 0.40f)
            close()
        }
        drawPath(arrow, if (lit) Dash.Green else Color(0xFF1C3524))
    }
}

/** Red warning triangle. Tap to turn the hazards on or off. */
@Composable
fun HazardButton(lit: Boolean, modifier: Modifier = Modifier, onTap: () -> Unit) {
    Canvas(
        modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown()
                if (waitForUpOrCancellation() != null) onTap()
            }
        }
    ) {
        val w = size.width
        val h = size.height
        val tri = Path().apply {
            moveTo(w / 2, h * 0.12f)
            lineTo(w * 0.86f, h * 0.84f)
            lineTo(w * 0.14f, h * 0.84f)
            close()
        }
        drawPath(tri, if (lit) Color(0xFFFF2B26) else Color(0xFF6E1414))
        drawPath(tri, if (lit) Color(0xFFFF8A80) else Color(0xFF2C0A0A), style = Stroke(w * 0.035f))
    }
}

/** P R N D gear gate down the top right. Drag the knob or tap a letter. */
@Composable
fun GearLever(gear: Gear, modifier: Modifier = Modifier, onGear: (Gear) -> Unit) {
    var dragY by remember { mutableStateOf<Float?>(null) }
    val paint = remember { textPaint() }
    val gears = remember { Gear.entries.toList() }
    fun slotY(height: Float, i: Int) = height * (0.10f + 0.235f * i)
    Canvas(
        modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                dragY = down.position.y
                drag(down.id) { ch -> dragY = ch.position.y; ch.consume() }
                val y = dragY ?: down.position.y
                val nearest = gears.indices.minBy { abs(slotY(size.height.toFloat(), it) - y) }
                onGear(gears[nearest])
                dragY = null
            }
        }
    ) {
        val w = size.width
        val h = size.height
        // Gate plate, running off the top of the screen
        drawRoundRect(Brush.horizontalGradient(listOf(Color(0xFF1B1C1E), Color(0xFF0D0D0E)), 0f, w),
            Offset(0f, -30f), Size(w * 0.92f, h * 0.90f + 30f), CornerRadius(14f))
        // Slot the lever runs in
        val sx = w * 0.60f
        drawLine(Color.Black, Offset(sx, slotY(h, 0)), Offset(sx, slotY(h, 3)), strokeWidth = w * 0.11f, cap = StrokeCap.Round)
        val lx = w * 0.20f
        gears.forEachIndexed { i, g ->
            val c = when {
                g != gear -> Color(0xFFB9BABD)
                g == Gear.D -> Dash.Green
                g == Gear.R -> Dash.Amber
                else -> Dash.White
            }
            label(paint, g.name, lx, slotY(h, i), w * 0.27f, c)
        }
        // Knob: follows the finger while dragging, otherwise sits on the selected gear
        val ky = (dragY ?: slotY(h, gears.indexOf(gear))).coerceIn(slotY(h, 0), slotY(h, 3))
        val kw = w * 0.56f
        val kh = h * 0.20f
        val kx = sx - kw / 2
        val ktop = ky - kh * 0.5f
        drawRoundRect(Color.Black.copy(alpha = 0.55f), Offset(kx + 3f, ktop + 6f), Size(kw, kh), CornerRadius(kw * 0.26f))
        drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF55585D), Color(0xFF17181A)), ktop, ktop + kh),
            Offset(kx, ktop), Size(kw, kh), CornerRadius(kw * 0.26f))
        for (i in 0..3) {
            val y = ktop + kh * (0.46f + i * 0.13f)
            drawLine(Color(0xFF0B0B0C), Offset(kx + kw * 0.14f, y), Offset(kx + kw * 0.86f, y), strokeWidth = 3f)
        }
        drawRoundRect(Dash.White, Offset(kx + kw * 0.26f, ktop + kh * 0.14f), Size(kw * 0.48f, kh * 0.16f), CornerRadius(4f))
    }
}

/** Tall gas pedal: where your thumb sits sets the power. Top edge = gentle, slide down = more, bottom = full. */
@Composable
fun GasPedal(modifier: Modifier = Modifier, onGas: (Float) -> Unit) {
    var depth by remember { mutableFloatStateOf(0f) }
    Canvas(
        modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                fun at(y: Float) = (0.15f + 0.85f * (y / size.height)).coerceIn(0.15f, 1f)
                depth = at(down.position.y); onGas(depth)
                drag(down.id) { ch -> depth = at(ch.position.y); onGas(depth); ch.consume() }
                depth = 0f; onGas(0f)
            }
        }
    ) {
        drawPedal(depth, vertical = true, ribs = 7)
    }
}

/** Small brake pedal: hold to stop. */
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
        drawPedal(if (pressed) 1f else 0f, vertical = false, ribs = 7)
    }
}

/** Ribbed rubber pedal. It tips away from the foot when pressed: smaller and darker. */
private fun DrawScope.drawPedal(depth: Float, vertical: Boolean, ribs: Int) {
    val squash = 1f - 0.08f * depth
    val w = size.width * squash
    val h = size.height * squash
    val o = Offset((size.width - w) / 2, (size.height - h) / 2)
    val radius = CornerRadius(min(w, h) * 0.16f)
    val shade = 0.35f * depth
    fun dark(c: Color) = androidx.compose.ui.graphics.lerp(c, Color.Black, shade)
    drawRoundRect(Color.Black.copy(alpha = 0.45f), o + Offset(3f, 7f), Size(w, h), radius)
    drawRoundRect(Brush.verticalGradient(listOf(dark(Color(0xFF6B6D70)), dark(Color(0xFF3F4144))), o.y, o.y + h), o, Size(w, h), radius)
    drawRoundRect(dark(Color(0xFF8B8D90)), o, Size(w, h), radius, style = Stroke(2.5f))
    for (i in 1..ribs) {
        if (vertical) {
            val x = o.x + w * i / (ribs + 1)
            drawLine(dark(Color(0xFF232426)), Offset(x, o.y + h * 0.05f), Offset(x, o.y + h * 0.95f), strokeWidth = w * 0.035f, cap = StrokeCap.Round)
        } else {
            val y = o.y + h * i / (ribs + 1)
            drawLine(dark(Color(0xFF232426)), Offset(o.x + w * 0.10f, y), Offset(o.x + w * 0.90f, y), strokeWidth = h * 0.035f, cap = StrokeCap.Round)
        }
    }
}

/** Thin vertical bar on the left of the windshield: how far the gas pedal is down. */
@Composable
fun GasBar(gas: () -> Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRect(Color.Black.copy(alpha = 0.28f), size = size)
        drawRect(Color.White.copy(alpha = 0.55f), size = size, style = Stroke(2f))
        val d = gas().coerceIn(0f, 1f)
        drawRect(
            Brush.horizontalGradient(listOf(Color(0xFFC63F12), Dash.Orange, Color(0xFFC63F12)), 0f, w),
            Offset(0f, h * (1f - d)), Size(w, h * d)
        )
    }
}

/** Gear wheel for the settings button, in the box where the game has its camera icon. */
@Composable
fun SettingsIcon(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val c = center
        val r = min(size.width, size.height) * 0.33f
        val ink = Color(0xFF2B2C2F)
        drawRoundRect(Color(0xCCB9BABC), size = size, cornerRadius = CornerRadius(size.height * 0.16f))
        for (i in 0 until 8) {
            val a = i * PI.toFloat() / 4f
            val dir = Offset(cos(a), sin(a))
            drawLine(ink, c + dir * (r * 0.7f), c + dir * (r * 1.22f), strokeWidth = r * 0.36f)
        }
        drawCircle(ink, r * 0.9f, c)
        drawCircle(Color(0xFFB9BABC), r * 0.42f, c)
    }
}
