package com.pranav.rcpilot

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.random.Random

private const val SLOTS = 16
private const val SLOT_LEN = 4.5f
private const val WORLD = SLOTS * SLOT_LEN     // 72: also a whole number of dash and post periods, so the wrap is invisible
private const val FAR = 80f
private const val NEAR = 0.32f
private const val DASH_PERIOD = 1.2f
private const val POST_PERIOD = 0.6f
private const val TOP_SPEED = 13f              // world units per second at 100% power

private const val LEFT_WALL = -4.6f
private const val RIGHT_WALL = 9.8f

private val Haze = Color(0xFFCFD4D3)

private class Block(val height: Float, val wall: Color, val glass: Color, val sign: Color, val floors: Int, val cols: Int)

private fun blocks(seed: Int): List<Block> {
    val r = Random(seed)
    val walls = listOf(
        Color(0xFFB9AE9C), Color(0xFF8E8F92), Color(0xFF6E7F86), Color(0xFFA8896F),
        Color(0xFFC8C2B4), Color(0xFF5E6B73), Color(0xFF8A6F5C), Color(0xFF4F7A78),
    )
    val signs = listOf(Color(0xFFB3262B), Color(0xFF28323C), Color(0xFF9C6A2A), Color(0xFF2E5C63), Color(0xFF7A2230))
    return List(SLOTS) {
        val h = 6f + r.nextFloat() * 11f
        Block(h, walls[r.nextInt(walls.size)], Color(0xFF1F2A33), signs[r.nextInt(signs.size)], (h / 1.05f).toInt(), 3 + r.nextInt(4))
    }
}

private fun wrap(v: Float, period: Float) = v - floor(v / period) * period

/**
 * The view through the windshield, drawn in code: a city road in perspective.
 * There is no camera on the car. The road only reflects what the motors are being told to do.
 *
 * @param speed -1..1 signed motor power; the road scrolls with it and runs backward in R
 * @param steer -1..1 wheel; the view swings towards the turn while moving
 */
@Composable
fun RoadScene(modifier: Modifier = Modifier, speed: () -> Float, steer: () -> Float) {
    var scroll by remember { mutableFloatStateOf(0f) }
    var sway by remember { mutableFloatStateOf(0f) }
    val left = remember { blocks(7) }
    val right = remember { blocks(23) }
    val path = remember { Path() }

    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { t ->
                val dt = if (last == 0L) 0f else ((t - last) / 1e9f).coerceAtMost(0.05f)
                last = t
                val v = speed()
                if (v != 0f) scroll = wrap(scroll + v * TOP_SPEED * dt, WORLD)
                val target = -steer() * min(1f, abs(v) * 3f) * 0.08f
                if (target != sway) sway += (target - sway) * min(1f, dt * 4f)
            }
        }
    }

    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val vx = w * 0.51f + sway * w
        val vy = h * 0.24f
        val f = h * 0.45f
        fun sx(x: Float, z: Float) = vx + f * x / z
        fun sy(y: Float, z: Float) = vy + f * (1f - y) / z

        fun ground(xa: Float, xb: Float, z0: Float, z1: Float, c: Color) {
            path.reset()
            path.moveTo(sx(xa, z0), sy(0f, z0))
            path.lineTo(sx(xb, z0), sy(0f, z0))
            path.lineTo(sx(xb, z1), sy(0f, z1))
            path.lineTo(sx(xa, z1), sy(0f, z1))
            path.close()
            drawPath(path, c)
        }

        fun addWall(x: Float, z0: Float, z1: Float, y0: Float, y1: Float) {
            path.moveTo(sx(x, z0), sy(y0, z0))
            path.lineTo(sx(x, z1), sy(y0, z1))
            path.lineTo(sx(x, z1), sy(y1, z1))
            path.lineTo(sx(x, z0), sy(y1, z0))
            path.close()
        }

        fun fog(c: Color, z: Float) = lerp(c, Haze, (z / WORLD).coerceIn(0f, 0.85f))

        fun buildings(list: List<Block>, x: Float, outward: Float) {
            val base = floor(scroll / SLOT_LEN).toInt()
            val s = wrap(scroll, SLOT_LEN)
            for (j in SLOTS - 1 downTo 0) {
                val zRaw = j * SLOT_LEN - s
                val z1 = zRaw + SLOT_LEN - 0.15f
                if (z1 < NEAR + 0.05f) continue
                val z0 = maxOf(zRaw, NEAR)
                val b = list[((base + j) % SLOTS + SLOTS) % SLOTS]
                val wall = fog(b.wall, z0)

                // Side facing the camera, then the wall along the road
                path.reset()
                val edge = sx(x, z0)
                val top = sy(b.height, z0)
                val foot = sy(0f, z0)
                path.moveTo(edge, foot)
                path.lineTo(edge + outward * w * 2f, foot)
                path.lineTo(edge + outward * w * 2f, top)
                path.lineTo(edge, top)
                path.close()
                drawPath(path, lerp(wall, Color.Black, 0.35f))

                path.reset()
                addWall(x, z0, z1, 0f, b.height)
                drawPath(path, wall)

                if (z0 < 30f) {
                    // Rows of windows
                    val glass = fog(b.glass, z0)
                    val floorH = (b.height - 1.4f) / b.floors
                    path.reset()
                    for (n in 0 until b.floors) {
                        val y = 1.4f + floorH * n
                        addWall(x, z0, z1, y + floorH * 0.28f, y + floorH * 0.78f)
                    }
                    drawPath(path, glass)
                    // Piers that split the rows into windows
                    if (z0 < 20f) {
                        val stroke = (f * 0.09f / maxOf(z0, 1f)).coerceAtMost(14f)
                        for (n in 1 until b.cols * 2) {
                            val z = z0 + (z1 - z0) * n / (b.cols * 2)
                            drawLine(wall, Offset(sx(x, z), sy(1.4f, z)), Offset(sx(x, z), sy(b.height, z)), strokeWidth = stroke)
                        }
                    }
                }
                // Shop front and its sign
                path.reset()
                addWall(x, z0, z1, 0f, 0.92f)
                drawPath(path, fog(Color(0xFF20262B), z0))
                path.reset()
                addWall(x, z0, z1, 0.92f, 1.34f)
                drawPath(path, fog(b.sign, z0))
            }
        }

        fun railing(x: Float, c: Color) {
            val s = wrap(scroll, POST_PERIOD)
            var z = POST_PERIOD - s
            while (z < 18f) {
                if (z > NEAR) {
                    val stroke = (f * 0.022f / z).coerceAtLeast(1f)
                    drawLine(c, Offset(sx(x, z), sy(0f, z)), Offset(sx(x, z), sy(0.62f, z)), strokeWidth = stroke)
                }
                z += POST_PERIOD
            }
            for (y in listOf(0.62f, 0.16f)) {
                path.reset()
                addWall(x, NEAR, 26f, y, y + 0.05f)
                drawPath(path, c)
            }
        }

        fun dashes(x: Float, c: Color) {
            val s = wrap(scroll, DASH_PERIOD)
            var z = DASH_PERIOD * 2 - s
            while (z > 0.6f) z -= DASH_PERIOD
            while (z < 44f) {
                val a = maxOf(z, NEAR)
                val b = z + 0.48f
                if (b > NEAR) ground(x - 0.12f, x + 0.12f, a, b, c)
                z += DASH_PERIOD
            }
        }

        // Sky
        drawRect(Brush.verticalGradient(listOf(Color(0xFF9DBFD4), Color(0xFFE6DFD0)), 0f, vy), size = Size(w, vy + 2f))
        // Ground
        drawRect(Brush.verticalGradient(listOf(Color(0xFF8F8378), Color(0xFF6A4F44)), vy, h), Offset(0f, vy), Size(w, h - vy))

        // Left pavement, main road, median, opposite road
        ground(-30f, -3.0f, NEAR, FAR, Color(0xFF8C6355))
        ground(-3.0f, 3.0f, NEAR, FAR, Color(0xFF2B2B2D))
        ground(-2.86f, -2.70f, NEAR, FAR, Color(0xFFD9A62A))
        ground(-2.62f, -2.54f, NEAR, FAR, Color(0xFFD9A62A))
        ground(3.0f, 3.14f, NEAR, FAR, Color(0xFF7D6A63))
        ground(3.14f, 3.56f, NEAR, FAR, Color(0xFF5A6B3A))
        ground(3.56f, 3.72f, NEAR, FAR, Color(0xFF9A7F76))
        ground(3.72f, 9.1f, NEAR, FAR, Color(0xFF2E2E30))
        ground(9.1f, 30f, NEAR, FAR, Color(0xFF8C6355))
        dashes(-1.05f, Color(0xFFEDEBE4))
        dashes(1.05f, Color(0xFFEDEBE4))
        dashes(6.4f, Color(0xFFC9C7C0))

        // Bridge far ahead, where the road ends in haze
        drawRect(Color(0xFF8AA3B8), Offset(vx - w * 0.075f, vy - h * 0.022f), Size(w * 0.15f, h * 0.022f))
        drawRect(Color(0xFFE2D9B8), Offset(vx - w * 0.075f, vy - h * 0.028f), Size(w * 0.15f, h * 0.006f))

        buildings(left, LEFT_WALL, -1f)
        buildings(right, RIGHT_WALL, 1f)
        railing(-4.05f, Color(0xFF17181A))
        railing(9.3f, Color(0xFF17181A))
    }
}
