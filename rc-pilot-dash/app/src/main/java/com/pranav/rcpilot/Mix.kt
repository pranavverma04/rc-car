package com.pranav.rcpilot

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Gear lever positions, top to bottom. */
enum class Gear { P, R, N, D }

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

    /**
     * Car-style driving: nothing moves without gas, like a real car.
     * @param reverse true in R
     * @param gas 0..1 pedal depth
     * @param wheel -1..1 steering wheel, right is positive
     * In R the steering is flipped so it feels like reversing a real car: wheel right = tail goes right.
     */
    fun car(reverse: Boolean, gas: Float, wheel: Float, limit: Int, trim: Float = 0f): Pair<Int, Int> {
        if (gas <= 0f) return 0 to 0
        val t = if (reverse) -gas else gas
        return tank(t, if (reverse) -wheel else wheel, limit, trim)
    }

    /** Full cabin input: the car only moves in D or R, with gas down and the brake up. */
    fun drive(gear: Gear, brake: Boolean, gas: Float, wheel: Float, limit: Int, trim: Float = 0f): Pair<Int, Int> {
        if (brake || gear == Gear.P || gear == Gear.N) return 0 to 0
        return car(gear == Gear.R, gas, wheel, limit, trim)
    }

    private fun dead(v: Float) = if (abs(v) < 0.06f) 0f else v.coerceIn(-1f, 1f)

    private fun power(v: Float, limit: Int, minPower: Int): Int {
        if (abs(v) < 0.02f) return 0
        val p = minPower + abs(v) * (limit - minPower)
        return (if (v > 0) p else -p).roundToInt()
    }
}
