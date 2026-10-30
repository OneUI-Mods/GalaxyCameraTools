package dev.ryuya.galaxycamera

import org.junit.Assert.*
import org.junit.Test

open class LensMotionTimingTest {
    @Test
    open fun shortTransitionsAreVisibleWithoutMakingLongTransitionsUnbounded() {
        assertEquals(320, LensMotionTiming.duration(144))
        assertEquals(454, LensMotionTiming.duration(336))
        assertEquals(1100, LensMotionTiming.duration(3000))
    }

    @Test
    open fun easingAcceleratesThenDeceleratesWithoutOvershoot() {
        val startStep: Float = LensMotionTiming.phase(.1f) - LensMotionTiming.phase(0f)
        val middleStep: Float = LensMotionTiming.phase(.5f) - LensMotionTiming.phase(.4f)
        val endStep: Float = LensMotionTiming.phase(1f) - LensMotionTiming.phase(.9f)
        assertTrue(middleStep > startStep * 2)
        assertEquals((startStep).toFloat(), (endStep).toFloat(), (.00001f).toFloat())
        var previous: Float = 0f
        for (i in 0..100) {
            val current = LensMotionTiming.phase(i / 100f)
            assertTrue(current >= previous && current <= 1f)
            previous = current
        }
        assertEquals((0).toDouble(), (LensMotionTiming.phase(-1f)).toDouble(), (0).toDouble())
        assertEquals((1).toDouble(), (LensMotionTiming.phase(2f)).toDouble(), (0).toDouble())
    }

    @Test
    open fun nativeHandoffUsesTheEasedPositionRatherThanWallTime() {
        val duration = LensMotionTiming.duration(336)
        assertEquals(0, LensMotionTiming.curveTime(-100, duration, 336))
        assertEquals(336, LensMotionTiming.curveTime(duration + 100, duration, 336))
        assertTrue(LensMotionTiming.curveTime(duration / 4, duration, 336) < 336 / 4)
        assertTrue(LensMotionTiming.curveTime(duration * 3 / 4, duration, 336) > 336 * 3 / 4)
    }
}
