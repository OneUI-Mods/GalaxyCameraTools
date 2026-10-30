package dev.ryuya.galaxycamera

import kotlin.jvm.JvmStatic

/* A 60 Hz display is not evidence that the camera stream can deliver 60 fps. */
object PreviewPolicy {
    @JvmStatic
    fun eligible(
        enabled: Boolean,
        mode: Int,
        makerType: Int,
        supportsFixed60: Boolean,
        minimumFrameDuration: Long,
        thermalStatus: Int,
    ): Boolean {
        return enabled &&
            mode == 0 &&
            makerType == 1 &&
            supportsFixed60 &&
            minimumFrameDuration > 0 &&
            minimumFrameDuration <= 16666667L &&
            thermalStatus < 3
    }
}
