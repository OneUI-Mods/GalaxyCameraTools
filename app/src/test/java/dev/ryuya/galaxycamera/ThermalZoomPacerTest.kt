package dev.ryuya.galaxycamera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalZoomPacerTest {
    @Test
    fun acceptsNewPreviewFramesWithoutReissuingForTheSameFrame() {
        val pacer = ThermalZoomPacer()
        assertTrue(pacer.accept(12, 0, false))
        assertFalse(pacer.accept(12, 16_000_000, false))
        assertTrue(pacer.accept(13, 33_000_000, false))
    }

    @Test
    fun resumesDuringAFrameStallAndAlwaysSendsTheFinalTarget() {
        val pacer = ThermalZoomPacer()
        assertTrue(pacer.accept(Long.MIN_VALUE, 0, false))
        assertFalse(pacer.accept(Long.MIN_VALUE, 95_000_000, false))
        assertTrue(pacer.accept(Long.MIN_VALUE, 96_000_000, false))
        assertTrue(pacer.accept(Long.MIN_VALUE, 97_000_000, true))
    }
}
