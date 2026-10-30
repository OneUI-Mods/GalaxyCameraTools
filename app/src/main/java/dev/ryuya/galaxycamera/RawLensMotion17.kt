package dev.ryuya.galaxycamera

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.animation.ValueAnimator.AnimatorUpdateListener
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.SizeF
import android.view.SurfaceView
import android.view.View
import kotlin.jvm.JvmStatic
import kotlin.math.log
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/* Visual lens handoff; never changes RAW capture requests, crop regions or saved settings. */
class RawLensMotion17(
    runtime: HostRuntime,
    context: Any?,
    layer: Any,
    from: Int,
    to: Int,
    completed: Runnable,
) {
    private val runtime: HostRuntime
    private val layer: Any
    private val snapshot: View
    private val blur: View
    private val preview: SurfaceView
    private val completed: Runnable
    private val ratio: Float
    private val stockCurve: MutableList<ZoomCurve.Point?>?
    private val curveDuration: Int
    private val previewScaleX: Float
    private val previewScaleY: Float
    private val previewPivotX: Float
    private val previewPivotY: Float
    private val previewFrameRate: Float
    private val snapshotFrameRate: Float
    private val previewClip: Rect?
    private val timeout: Runnable = Runnable { this.finish() }
    private var zoom: ValueAnimator? = null
    private var fade: ValueAnimator? = null
    private var previewReady: Boolean = false
    private var travelFinished: Boolean = false
    private var fadeFinished: Boolean = false
    private var finished: Boolean = false
    private var progress: Float = 0f
    private val animatedClip: Rect = Rect()

    init {
        this.runtime = runtime
        this.layer = layer
        this.completed = completed
        stockCurve = StockZoomCurves.read(runtime, from, to)
        curveDuration = if (stockCurve == null) 0 else ZoomCurve.duration(stockCurve)
        val binding = Reflect.get(layer, "mViewBinding")
        snapshot = (Reflect.get(binding!!, "h") as View?)!!
        blur = (Reflect.get(binding!!, "g") as View?)!!
        val candidate =
            Reflect.call(Reflect.call(context!!, "getPreviewManager")!!, "getSurfaceView")
                as SurfaceView?
        check(!(candidate == null || candidate.width <= 0 || candidate.height <= 0)) {
            "Preview surface is not laid out"
        }
        preview = candidate
        ratio =
            RawLensMotion17.fieldOfViewScale(runtime, to) /
                RawLensMotion17.fieldOfViewScale(runtime, from)
        check(!(!java.lang.Float.isFinite(ratio) || ratio < 0.05f || ratio > 20f)) {
            "Unknown lens field of view"
        }
        previewScaleX = preview.getScaleX()
        previewScaleY = preview.getScaleY()
        previewPivotX = preview.getPivotX()
        previewPivotY = preview.getPivotY()
        previewClip = preview.getClipBounds()
        previewFrameRate = preview.getRequestedFrameRate()
        snapshotFrameRate = snapshot.getRequestedFrameRate()
    }

    fun owns(candidate: Any?): Boolean {
        return !finished && layer === candidate
    }

    fun start() {
        try {
            Reflect.call(layer, "cancelAllAnimator")
            val type =
                Class.forName(
                    "com.samsung.android.app.galaxyraw.interfaces.PreviewAnimationLayerManager\$PreviewAnimationType",
                    false,
                    runtime.loader,
                )
            Reflect.field(layer.javaClass, "mCurrentAnimationType")!!.set(
                layer,
                Reflect.value(type, "PREVIEW_CHANGE"),
            )
            Reflect.field(layer.javaClass, "mSnapShotTaken")!!.setBoolean(layer, true)
        } catch (error: ReflectiveOperationException) {
            runtime.fail(error)
            finish()
            return
        }
        blur.setAlpha(1f)
        blur.setVisibility(if (ratio < 1f) View.VISIBLE else View.GONE)
        snapshot.setVisibility(View.VISIBLE)
        snapshot.setAlpha(1f)
        snapshot.setPivotX(snapshot.getWidth() / 2f)
        snapshot.setPivotY(snapshot.getHeight() / 2f)
        snapshot.setRequestedFrameRate(60f)
        preview.setRequestedFrameRate(60f)
        runtime.main.postDelayed(timeout, 2000)
        startTravel()
        ModernHook.log(
            ("Expert RAW lens motion: field-of-view ratio=" +
                ratio +
                "; stock curve=" +
                (stockCurve != null))
        )
    }

    fun onPreviewReady() {
        if (finished || previewReady) return
        previewReady = true
        if (ratio < 1f) {
            transformPreview(1f + (1f / ratio - 1f) * (1f - progress))
            startFade()
        } else if (travelFinished) {
            startFade()
        }
    }

    private fun startTravel() {
        zoom = ValueAnimator.ofFloat(0f, 1f)
        // Prefer the exact numeric curve read from the installed stock Camera at runtime.
        zoom!!.setInterpolator(
            TimeInterpolator { fraction: Float ->
                LensMotionTiming.phase(fraction)
            }
        )
        zoom!!.setDuration(
            LensMotionTiming.duration(
                    if (stockCurve == null) (if (min(ratio, 1f / ratio) < 0.34f) 800 else 200)
                    else curveDuration
                )
                .toLong()
        )
        zoom!!.addUpdateListener(
            AnimatorUpdateListener { value: ValueAnimator? ->
                if (finished) return@AnimatorUpdateListener
                progress = value!!.getAnimatedValue() as Float
                if (stockCurve != null) {
                    val first = stockCurve.get(0)!!.zoom
                    val last = stockCurve.get(stockCurve.size - 1)!!.zoom
                    val current =
                        ZoomCurve.valueAt(stockCurve, Math.round(progress * curveDuration))
                    progress = max(0f, min(1f, (current - first) / (last - first)))
                }
                if (ratio > 1f) {
                    val scale: Float = 1f + (ratio - 1f) * progress
                    snapshot.setScaleX(scale)
                    snapshot.setScaleY(scale)
                } else {
                    val scale: Float = 1f + (ratio - 1f) * progress
                    snapshot.setScaleX(scale)
                    snapshot.setScaleY(scale)
                    if (previewReady) transformPreview(1f + (1f / ratio - 1f) * (1f - progress))
                }
            }
        )
        zoom!!.addListener(
            object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (finished) return
                    travelFinished = true
                    if (ratio < 1f) {
                        if (fadeFinished) finish()
                    } else if (previewReady) startFade()
                }
            }
        )
        zoom!!.start()
    }

    private fun transformPreview(scale: Float) {
        preview.setPivotX(preview.getWidth() / 2f)
        preview.setPivotY(preview.getHeight() / 2f)
        preview.setScaleX(previewScaleX * scale)
        preview.setScaleY(previewScaleY * scale)
        val insetX = Math.round(preview.getWidth() * (1f - 1f / scale) / 2f)
        val insetY = Math.round(preview.getHeight() * (1f - 1f / scale) / 2f)
        animatedClip.set(insetX, insetY, preview.getWidth() - insetX, preview.getHeight() - insetY)
        if (previewClip != null) animatedClip.intersect(previewClip)
        preview.setClipBounds(animatedClip)
    }

    private fun startFade() {
        if (finished || fade != null) return
        fade = ValueAnimator.ofFloat(snapshot.getAlpha(), 0f)
        fade!!.setDuration(90)
        fade!!.addUpdateListener(
            AnimatorUpdateListener { value: ValueAnimator? ->
                val alpha: Float = value!!.getAnimatedValue() as Float
                snapshot.setAlpha(alpha)
                blur.setAlpha(alpha)
            }
        )
        fade!!.addListener(
            object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    fadeFinished = true
                    if (!finished && (ratio >= 1f || travelFinished)) finish()
                }
            }
        )
        fade!!.start()
    }

    fun finish() {
        if (finished) return
        finished = true
        runtime.main.removeCallbacks(timeout)
        if (zoom != null) zoom!!.cancel()
        if (fade != null) fade!!.cancel()
        preview.setScaleX(previewScaleX)
        preview.setScaleY(previewScaleY)
        preview.setPivotX(previewPivotX)
        preview.setPivotY(previewPivotY)
        preview.setClipBounds(previewClip)
        preview.setRequestedFrameRate(previewFrameRate)
        snapshot.setScaleX(1f)
        snapshot.setScaleY(1f)
        snapshot.setAlpha(1f)
        snapshot.setRequestedFrameRate(snapshotFrameRate)
        snapshot.setVisibility(View.GONE)
        blur.setAlpha(1f)
        blur.setVisibility(View.GONE)
        try {
            Reflect.call(layer, "cancelPreviewAnimation")
        } catch (error: ReflectiveOperationException) {
            runtime.fail(error)
        }
        completed.run()
        ModernHook.log("Expert RAW lens motion: restored; new preview=" + previewReady)
    }

    companion object {
        @JvmStatic
        @Throws(Exception::class)
        private fun fieldOfViewScale(runtime: HostRuntime, cameraId: Int): Float {
            val ids =
                Class.forName(
                    "com.samsung.android.app.galaxyraw.engine.request.CameraId",
                    false,
                    runtime.loader,
                )
            val deviceId: Int =
                Reflect.method(ids, "getDeviceId", Int::class.javaPrimitiveType)!!.invoke(
                    null,
                    cameraId,
                ) as Int
            val manager =
                runtime.context.getSystemService<CameraManager?>(CameraManager::class.java)
            val characteristics = manager.getCameraCharacteristics(deviceId.toString())
            val focalLengths =
                characteristics.get<FloatArray?>(
                    CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
                )
            val sensor =
                characteristics.get<SizeF?>(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            check(
                !(focalLengths == null ||
                    focalLengths.size == 0 ||
                    sensor == null ||
                    sensor.getWidth() <= 0)
            ) {
                "Lens geometry is unavailable"
            }
            return focalLengths[0] / sensor.getWidth()
        }
    }
}
