package dev.ryuya.galaxycamera

import kotlin.jvm.JvmStatic
import kotlin.math.max

/* Aligns the outgoing picture and incoming live view to the same interpolated field of view. */
object LensPreviewGeometry {
    @JvmStatic
    fun snapshotScale(from: Float, current: Float): Float {
        return current / from
    }

    @JvmStatic
    fun incomingScale(target: Float, current: Float): Float {
        return max(1f, current / target)
    }
}
