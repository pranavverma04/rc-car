package com.pranav.rcpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MixTest {
    @Test fun stillStaysStill() = assertEquals(0 to 0, Mix.tank(0f, 0f, 100))

    @Test fun smallWobbleIsIgnored() = assertEquals(0 to 0, Mix.tank(0.03f, -0.04f, 100))

    @Test fun fullForwardHitsTheLimit() = assertEquals(70 to 70, Mix.tank(1f, 0f, 70))

    @Test fun fullReverse() = assertEquals(-100 to -100, Mix.tank(-1f, 0f, 100))

    @Test fun steerRightWhileStillSpinsInPlace() {
        val (l, r) = Mix.tank(0f, 1f, 100)
        assertEquals(100, l)
        assertEquals(-100, r)
    }

    @Test fun curveRightKeepsBothWheelsForward() {
        val (l, r) = Mix.tank(1f, 0.5f, 100)
        assertTrue("left faster than right on a right curve: $l $r", l > r)
        assertTrue("right wheel still forward: $r", r > 0)
    }

    @Test fun gentleInputStartsAtMinimumPower() {
        val (l, _) = Mix.tank(0.1f, 0f, 100)
        assertTrue("motor gets enough power to move: $l", l >= Mix.MIN_POWER)
    }

    @Test fun positiveTrimSlowsRight() {
        val (l, r) = Mix.tank(1f, 0f, 100, trim = 0.1f)
        assertTrue(r < l)
    }

    @Test fun carNeedsGasToMove() = assertEquals(0 to 0, Mix.car(false, 0f, 1f, 100))

    @Test fun carFullGasForward() = assertEquals(100 to 100, Mix.car(false, 1f, 0f, 100))

    @Test fun carFullGasReverse() = assertEquals(-100 to -100, Mix.car(true, 1f, 0f, 100))

    @Test fun carReverseWheelRightSwingsTailRight() {
        // Reversing with the wheel right: left side runs backward faster, so the nose swings left like a real car
        val (l, r) = Mix.car(true, 0.6f, 0.5f, 100)
        assertTrue(l < r)
    }
}
