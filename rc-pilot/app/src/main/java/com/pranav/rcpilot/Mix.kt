package com.pranav.rcpilot

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Turns stick input into left/right motor power for a two-wheel tank-drive car. */
object Mix {
    /** TT motors do not turn below roughly this duty, so the smallest non-zero output starts here. */
    const val MIN_POWER = 28

    /**
     * @param throttle -1..1, forward is positive
     * @param steer -1..1, right is positive
     * @param limit top motor power in percent
     * @param trim -0.25..0.25; positive slows the right side (use it when the car pulls left)
     * @return left and right motor power, each -100..100
     */
    fun tank(throttle: Float, steer: Float, limit: Int, trim: Float = 0f, minPower: Int = MIN_POWER): Pair<Int, Int> {
        val t = dead(throttle)
        val s = dead(steer)
        // Full spin when standing still, gentler turns at speed so curves stay smooth
        val turn = s * (1f - 0.5f * abs(t))
        var l = t + turn
        var r = t - turn
        val m = max(1f, max(abs(l), abs(r)))
        l /= m
        r /= m
        if (trim > 0) r *= 1 - trim else l *= 1 + trim
        val top = limit.coerceIn(minPower, 100)
        return power(l, top, minPower) to power(r, top, minPower)
    }

    private fun dead(v: Float) = if (abs(v) < 0.06f) 0f else v.coerceIn(-1f, 1f)

    private fun power(v: Float, limit: Int, minPower: Int): Int {
        if (abs(v) < 0.02f) return 0
        val p = minPower + abs(v) * (limit - minPower)
        return (if (v > 0) p else -p).roundToInt()
    }
}
