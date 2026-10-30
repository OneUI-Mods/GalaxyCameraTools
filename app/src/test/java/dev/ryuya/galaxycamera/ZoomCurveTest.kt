package dev.ryuya.galaxycamera

import java.util.List
import org.junit.Assert.*
import org.junit.Test

open class ZoomCurveTest {
    private val curve: MutableList<ZoomCurve.Point?>? =
        List.of<ZoomCurve.Point?>(
            ZoomCurve.Point(1f, 32),
            ZoomCurve.Point(1.2f, 16),
            ZoomCurve.Point(3f, 0),
        )

    @Test
    open fun nativeEasingAndDurationArePreserved() {
        assertEquals(48, ZoomCurve.duration(curve!!))
        assertEquals(
            (1.1f).toFloat(),
            (ZoomCurve.valueAt(curve!!, 16)).toFloat(),
            (.0001f).toFloat(),
        )
        assertEquals(
            (2.1f).toFloat(),
            (ZoomCurve.valueAt(curve!!, 40)).toFloat(),
            (.0001f).toFloat(),
        )
        assertEquals((1f).toFloat(), (ZoomCurve.valueAt(curve!!, -10)).toFloat(), (0f).toFloat())
        assertEquals((3f).toFloat(), (ZoomCurve.valueAt(curve!!, 1000)).toFloat(), (0f).toFloat())
    }

    @Test
    open fun missedFramesSampleCurrentTimeWithoutReplayingOldSteps() {
        assertEquals(
            (2.55f).toFloat(),
            (ZoomCurve.valueAt(curve!!, 44)).toFloat(),
            (.0001f).toFloat(),
        )
        assertEquals(2, ZoomCurve.nextIndex(curve!!, 44))
        assertEquals(2, ZoomCurve.nextIndex(curve!!, 999))
    }

    @Test
    open fun reverseDirectionDoesNotOvershoot() {
        val reverse = List.of<ZoomCurve.Point?>(ZoomCurve.Point(5f, 33), ZoomCurve.Point(1f, 0))
        assertEquals(33, ZoomCurve.duration(reverse))
        assertTrue(ZoomCurve.valueAt(reverse, 16) > 1 && ZoomCurve.valueAt(reverse, 16) < 5)
        assertEquals((1f).toFloat(), (ZoomCurve.valueAt(reverse, 33)).toFloat(), (0f).toFloat())
    }

    @Test
    open fun nativeSubsectionRetainsIntermediateEasingAndEndpoints() {
        val section = ZoomCurve.between(curve!!, 1.1f, 2.1f)
        assertNotNull(section)
        assertEquals(24, ZoomCurve.duration(section!!))
        assertEquals((1.1f).toFloat(), (section!!.get(0)!!.zoom).toFloat(), (.0001f).toFloat())
        assertEquals(
            (1.2f).toFloat(),
            (ZoomCurve.valueAt(section!!, 16)).toFloat(),
            (.0001f).toFloat(),
        )
        assertEquals(
            (2.1f).toFloat(),
            (ZoomCurve.valueAt(section!!, 24)).toFloat(),
            (.0001f).toFloat(),
        )
        assertNull(ZoomCurve.between(curve!!, 3f, 1f))
    }

    @Test
    open fun exactNativePairTakesPrecedenceOverAnEarlierWiderCurve() {
        val wider = List.of<ZoomCurve.Point?>(ZoomCurve.Point(.6f, 100), ZoomCurve.Point(5f, 0))
        val exact =
            List.of<ZoomCurve.Point?>(
                ZoomCurve.Point(1f, 400),
                ZoomCurve.Point(3f, 16),
                ZoomCurve.Point(3f, 0),
            )
        val selected =
            ZoomCurve.transition(List.of<MutableList<ZoomCurve.Point?>?>(wider, exact), 1f, 3f)
        assertEquals(exact, selected)
        assertEquals(416, ZoomCurve.duration(selected!!))
        assertNotNull(ZoomCurve.transition(List.of<MutableList<ZoomCurve.Point?>?>(wider), 1f, 3f))
    }

    @Test
    open fun invalidCurveIsRejectedBeforeTakingOverTheNativeRunner() {
        assertEquals(
            -1,
            ZoomCurve.duration(
                List.of<ZoomCurve.Point?>(ZoomCurve.Point(1f, -1), ZoomCurve.Point(3f, 0))
            ),
        )
        assertEquals(
            -1,
            ZoomCurve.duration(
                List.of<ZoomCurve.Point?>(ZoomCurve.Point(Float.NaN, 16), ZoomCurve.Point(3f, 0))
            ),
        )
    }
}
