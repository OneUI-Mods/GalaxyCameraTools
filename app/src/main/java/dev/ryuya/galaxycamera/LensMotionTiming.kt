package dev.ryuya.galaxycamera

import kotlin.jvm.JvmStatic
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/* A visible, gentle acceleration and deceleration shared by Camera and Expert RAW. */
object LensMotionTiming {
    @JvmStatic
    fun duration(nativeMillis: Int): Int {
        return max(320, min(1100, Math.round(nativeMillis * 1.35f)))
    }

    @JvmStatic
    fun phase(fraction: Float): Float {
        val t = max(0f, min(1f, fraction))
        return t * t * (3f - 2f * t)
    }

    @JvmStatic
    fun curveTime(elapsed: Int, duration: Int, nativeDuration: Int): Int {
        return Math.round(LensMotionTiming.phase(elapsed / duration.toFloat()) * nativeDuration)
    }
}
