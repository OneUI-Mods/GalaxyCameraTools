package dev.ryuya.galaxycamera

/** Paces intermediate camera requests without changing display samples or the final zoom. */
internal class ThermalZoomPacer {
    private var lastRequestNanos = Long.MIN_VALUE
    private var lastPreviewFrame = Long.MIN_VALUE

    fun accept(previewFrame: Long, nowNanos: Long, finished: Boolean): Boolean {
        if (!finished && lastRequestNanos != Long.MIN_VALUE && previewFrame == lastPreviewFrame &&
            nowNanos - lastRequestNanos < MAX_WAIT_NANOS
        ) return false
        lastPreviewFrame = previewFrame
        lastRequestNanos = nowNanos
        return true
    }

    private companion object {
        // A lens switch can pause results briefly. Keep taking the latest zoom during the gap.
        const val MAX_WAIT_NANOS = 96_000_000L
    }
}
