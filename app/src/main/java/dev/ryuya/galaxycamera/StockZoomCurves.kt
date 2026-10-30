package dev.ryuya.galaxycamera

import java.util.ArrayList
import kotlin.jvm.JvmStatic
import kotlin.math.log
import org.json.JSONArray
import org.json.JSONException

/* Shares only numeric, runtime-read stock curves; no Samsung code or images are copied. */
object StockZoomCurves {
    @JvmStatic
    @Throws(ReflectiveOperationException::class, JSONException::class)
    fun encode(nativeCurves: MutableMap<*, *>): String? {
        val output = JSONArray()
        for (value in nativeCurves.values) {
            if (value !is MutableList<*>) continue
            val curve = JSONArray()
            for (point in value) {
                curve.put(
                    JSONArray()
                        .put((Reflect.get(point!!, "a") as Float).toDouble())
                        .put(Reflect.get(point!!, "b") as Int)
                )
            }
            output.put(curve)
        }
        return output.toString()
    }

    @JvmStatic
    @Throws(JSONException::class)
    fun decode(encoded: String?): MutableList<MutableList<ZoomCurve.Point?>?> {
        if (encoded == null || encoded.length > 40000) throw JSONException("Curve data too large")
        val input = JSONArray(encoded)
        if (input.length() > 16) throw JSONException("Too many curves")
        val curves = ArrayList<MutableList<ZoomCurve.Point?>?>()
        for (index in 0 until input.length()) {
            val items = input.getJSONArray(index)
            if (items.length() > 1000) throw JSONException("Too many points")
            val points = ArrayList<ZoomCurve.Point?>()
            for (item in 0 until items.length()) {
                val point = items.getJSONArray(item)
                points.add(ZoomCurve.Point(point.getDouble(0).toFloat(), point.getInt(1)))
            }
            val duration = ZoomCurve.duration(points)
            if (duration <= 0 || duration > 3000) throw JSONException("Invalid curve")
            curves.add(points)
        }
        return curves
    }

    @JvmStatic
    fun read(runtime: HostRuntime, fromCamera: Int, toCamera: Int): MutableList<ZoomCurve.Point?>? {
        try {
            val from = StockZoomCurves.lensZoom(fromCamera)
            val to = StockZoomCurves.lensZoom(toCamera)
            if (from <= 0 || to <= 0 || from == to) return null
            val data =
                runtime.context
                    .getContentResolver()
                    .call(ConfigProvider.URI!!, "zoom_curves_read", null, null)
            if (data == null) return null
            return ZoomCurve.transition(
                StockZoomCurves.decode(data.getString("curves", "[]")),
                from,
                to,
            )
        } catch (error: RuntimeException) {
            ModernHook.log("Stock zoom curve unavailable; using stock easing fallback")
        } catch (error: JSONException) {
            ModernHook.log("Stock zoom curve unavailable; using stock easing fallback")
        }
        return null
    }

    @JvmStatic
    private fun lensZoom(cameraId: Int): Float {
        return when (cameraId) {
            0,
            102 -> 1f
            100 -> 3f
            101 -> .6f
            104 -> 5f
            else -> -1f
        }
    }
}
