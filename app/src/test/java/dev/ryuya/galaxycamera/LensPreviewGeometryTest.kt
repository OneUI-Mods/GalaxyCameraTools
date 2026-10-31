package dev.ryuya.galaxycamera

import org.junit.Assert.*
import org.junit.Test

open class LensPreviewGeometryTest {
    @Test
    open fun outgoingPictureAndIncomingViewHaveTheSameFieldOfViewDuringZoomOut() {
        val from: Float = 5f
        val target: Float = 1f
        for (current in floatArrayOf(5f, 4f, 3f, 2f, 1f)) {
            assertEquals(
                (from * LensPreviewGeometry.snapshotScale(from, current)).toFloat(),
                (target * LensPreviewGeometry.incomingScale(target, current)).toFloat(),
                (0.00001f).toFloat(),
            )
        }
    }

    @Test
    open fun incomingPreviewNeverShrinksToRevealUnpaintedEdges() {
        for (target in floatArrayOf(0.6f, 1f, 3f, 5f)) for (current in
            floatArrayOf(0.6f, 1f, 2f, 3f, 5f)) assertTrue(
            LensPreviewGeometry.incomingScale(target, current) >= 1f
        )
    }

    @Test
    open fun completedTransitionReturnsIncomingPreviewToItsOriginalScale() {
        for (target in floatArrayOf(0.6f, 1f, 3f, 5f)) assertEquals(
            (1f).toFloat(),
            (LensPreviewGeometry.incomingScale(target, target)).toFloat(),
            (0f).toFloat(),
        )
    }
}
