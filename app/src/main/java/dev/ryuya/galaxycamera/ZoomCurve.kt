package dev.ryuya.galaxycamera

import java.util.ArrayList
import java.util.List
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/* Time-based sampling preserves native easing, endpoints and duration even after missed frames. */
object ZoomCurve {
    @JvmStatic
    fun duration(points: MutableList<ZoomCurve.Point?>): Int {
        var total: Int = 0
        if (points.size < 2 || points.size > 1000) return -1
        for (index in points.indices) {
            val point = points.get(index)
            if (
                !java.lang.Float.isFinite(point!!.zoom) ||
                    point!!.zoom <= 0 ||
                    point!!.delayMillis < 0 ||
                    point!!.delayMillis > 1000 ||
                    (index < points.size - 1 && point!!.delayMillis == 0)
            )
                return -1
            total += point!!.delayMillis
        }
        return if (points.get(points.size - 1)!!.delayMillis == 0) total else -1
    }

    @JvmStatic
    fun valueAt(points: MutableList<ZoomCurve.Point?>, elapsedMillis: Int): Float {
        var elapsed = max(0, elapsedMillis)
        for (index in 0 until points.size - 1) {
            val point = points.get(index)
            if (elapsed < point!!.delayMillis) {
                return (point!!.zoom +
                    (points.get(index + 1)!!.zoom - point!!.zoom) * elapsed / point!!.delayMillis)
            }
            elapsed -= point!!.delayMillis
        }
        return points.get(points.size - 1)!!.zoom
    }

    @JvmStatic
    fun transition(
        curves: MutableList<MutableList<ZoomCurve.Point?>?>,
        from: Float,
        to: Float,
    ): MutableList<ZoomCurve.Point?>? {
        // Stock Camera checks an exact pair before taking a subsection of a wider curve.
        for (curve in curves) {
            if (
                !curve!!.isEmpty() &&
                    curve!!.get(0)!!.zoom == from &&
                    curve!!.get(curve!!.size - 1)!!.zoom == to &&
                    ZoomCurve.between(curve!!, from, to) != null
            )
                return List.copyOf<ZoomCurve.Point?>(curve)
        }
        for (curve in curves) {
            val section = ZoomCurve.between(curve!!, from, to)
            if (section != null) return section
        }
        return null
    }

    @JvmStatic
    fun between(
        source: MutableList<ZoomCurve.Point?>,
        from: Float,
        to: Float,
    ): MutableList<ZoomCurve.Point?>? {
        if (ZoomCurve.duration(source) <= 0 || from == to) return null
        val first = source.get(0)!!.zoom
        val last = source.get(source.size - 1)!!.zoom
        val increasing: Boolean = last > first
        if (
            (to > from) != increasing ||
                from < min(first, last) ||
                from > max(first, last) ||
                to < min(first, last) ||
                to > max(first, last)
        )
            return null
        for (i in 1 until source.size) {
            if ((source.get(i)!!.zoom - source.get(i - 1)!!.zoom) * (if (increasing) 1 else -1) < 0)
                return null
        }
        val start = ZoomCurve.timeAt(source, from)
        val end = ZoomCurve.timeAt(source, to)
        if (end <= start) return null
        val points = ArrayList<ZoomCurve.Point?>()
        var absolute: Int = 0
        var previousTime: Int = start
        var previousZoom: Float = from
        for (point in source) {
            if (absolute > start && absolute < end) {
                points.add(ZoomCurve.Point(previousZoom, absolute - previousTime))
                previousZoom = point!!.zoom
                previousTime = absolute
            }
            absolute += point!!.delayMillis
        }
        points.add(ZoomCurve.Point(previousZoom, end - previousTime))
        points.add(ZoomCurve.Point(to, 0))
        return points
    }

    @JvmStatic
    private fun timeAt(source: MutableList<ZoomCurve.Point?>, zoom: Float): Int {
        var time: Int = 0
        for (i in 0 until source.size - 1) {
            val point = source.get(i)
            val next = source.get(i + 1)
            if (
                zoom >= Math.min(point!!.zoom, next!!.zoom) &&
                    zoom <= Math.max(point!!.zoom, next!!.zoom)
            ) {
                if (point!!.zoom == next!!.zoom) return time
                return (time +
                    Math.round(
                        point!!.delayMillis * (zoom - point!!.zoom) / (next!!.zoom - point!!.zoom)
                    ))
            }
            time += point!!.delayMillis
        }
        return time
    }

    @JvmStatic
    fun nextIndex(points: MutableList<ZoomCurve.Point?>, elapsedMillis: Int): Int {
        var time: Int = 0
        for (index in 0 until points.size - 1) {
            time += points.get(index)!!.delayMillis
            if (time >= elapsedMillis) return index + 1
        }
        return points.size - 1
    }

    @JvmRecord
    data class Point(val zoom: Float, val delayMillis: Int) {
        override fun toString(): String = "Point[zoom=$zoom, delayMillis=$delayMillis]"
    }
}
