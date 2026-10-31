package dev.ryuya.galaxycamera

import android.animation.Animator
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.hardware.camera2.TotalCaptureResult
import android.os.Handler
import android.view.Choreographer
import android.view.Choreographer.FrameCallback
import android.view.View
import io.github.libxposed.api.XposedInterface.Chain
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.ArrayList
import java.util.concurrent.Executor
import java.util.function.Consumer
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmField
import kotlin.math.log
import kotlin.math.max
import kotlin.math.round
import org.json.JSONException

/*
 * Samples Samsung's original nonlinear zoom curve at display time, without delayed-step backlog.
 */
class PhotoZoomTiming17(runtime: HostRuntime, camera: CameraHooks17) {
    private val runtime: HostRuntime
    private val camera: CameraHooks17
    private val selecting: ThreadLocal<PhotoZoomTiming17.Selection?> =
        ThreadLocal<PhotoZoomTiming17.Selection?>()
    private var nativeStep: Constructor<*>? = null
    private var settingKey: Class<*>? = null
    private var zoomKey: Any? = null
    @Volatile private var motion: PhotoZoomTiming17.Motion? = null
    private var nativeAnimator: WeakReference<ValueAnimator?> = WeakReference<ValueAnimator?>(null)

    init {
        this.runtime = runtime
        this.camera = camera
    }

    @Throws(Exception::class)
    fun install() {
        camera.onZoomResult(
            Consumer { result: TotalCaptureResult? ->
                val active: PhotoZoomTiming17.Motion? = motion
                val transition: PhotoPreviewTransition17? =
                    if (active == null) null else active.display
                if (transition != null) transition.observe(result!!)
            }
        )
        val zoomView =
            Class.forName(
                "com.sec.android.app.camera.layer.keyscreen.zoom.ZoomView",
                false,
                runtime.loader,
            )
        ModernHook.findAndHookMethod(
            zoomView,
            "initialize",
            object : ModernHook.Callback() {
                override fun afterHookedMethod(parameter: ModernHook.Param) {
                    try {
                        val source = Reflect.get(parameter.thisObject!!, "k")
                        val curves =
                            StockZoomCurves.encode(
                                (Reflect.get(source!!, "a") as MutableMap<*, *>?)!!
                            )
                        runtime.worker.post(
                            Runnable {
                                try {
                                    runtime.context
                                        .getContentResolver()
                                        .call(
                                            ConfigProvider.URI!!,
                                            "zoom_curves_publish",
                                            curves,
                                            null,
                                        )
                                } catch (error: RuntimeException) {
                                    ModernHook.log("Stock curve sharing unavailable")
                                }
                            }
                        )
                    } catch (error: ReflectiveOperationException) {
                        ModernHook.log("Stock curve sharing unavailable")
                    } catch (error: JSONException) {
                        ModernHook.log("Stock curve sharing unavailable")
                    }
                }
            },
        )
        val stepType = Class.forName("v9.m", false, runtime.loader)
        nativeStep =
            stepType.getDeclaredConstructor(
                zoomView,
                Long::class.javaPrimitiveType,
                ArrayList::class.java,
                Int::class.javaPrimitiveType,
            )
        nativeStep!!.setAccessible(true)
        settingKey = Class.forName("l9.f0", false, runtime.loader)
        zoomKey = Reflect.value(settingKey, "ZOOM_VALUE")
        ModernHook.hookAllConstructors(
            stepType,
            object : ModernHook.Callback() {
                override fun afterHookedMethod(parameter: ModernHook.Param) {
                    val selection = selecting.get()
                    if (
                        selection != null &&
                            parameter.args[0] === selection.view &&
                            parameter.args[3] as Int == 1
                    ) {
                        selection.runner = parameter.thisObject as Runnable?
                        selection.generation = parameter.args[1] as Long
                        selection.points = parameter.args[2] as ArrayList<*>?
                    }
                }
            },
        )
        // The stock SUPER_STEADY category bypasses H2's animation entirely.
        // Limit this override to an actual H2 invocation, never capability or touch checks.
        ModernHook.findAndHookMethod(
            zoomView,
            "x2",
            object : ModernHook.Callback() {
                override fun beforeHookedMethod(call: ModernHook.Param) {
                    val active = selecting.get()
                    if (active != null && active.view === call.thisObject && active.steady)
                        call.setResult(true)
                }
            },
        )
        val nativeListener = Class.forName("v9.p", false, runtime.loader)
        ModernHook.findAndHookMethod(
            nativeListener,
            "onAnimationStart",
            Animator::class.java,
            object : ModernHook.Callback() {
                @Throws(Exception::class)
                override fun beforeHookedMethod(call: ModernHook.Param) {
                    val active = selecting.get()
                    val patternPrefix0 =
                        active == null ||
                            Reflect.get(call.thisObject!!, "b") !== active.view ||
                            Reflect.get(call.thisObject!!, "a") as Int != 0
                    val animator = if (!patternPrefix0) call.args[0] as? ValueAnimator else null
                    if (patternPrefix0 || animator == null) return
                    animator.setDuration(
                        LensMotionTiming.duration(animator.getDuration().toInt()).toLong()
                    )
                    animator.setInterpolator(
                        TimeInterpolator { fraction: Float ->
                            LensMotionTiming.phase(fraction)
                        }
                    )
                    nativeAnimator = WeakReference<ValueAnimator?>(animator)
                    ModernHook.log(
                        ("Lens motion: native animator; mode=" +
                            active.mode +
                            "; steady=" +
                            active.steady +
                            "; duration=" +
                            animator.getDuration())
                    )
                }
            },
        )
        ModernHook.deoptimize(
            Reflect.method(
                zoomView,
                "H2",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )!!
        )
        ModernHook.aroundMethod(
            Reflect.method(
                zoomView,
                "H2",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )!!,
            ModernHook.Around { chain: Chain? ->
                val mode = camera.mode()
                if (!runtime.enabled(ConfigProvider.LENS_MOTION) || (mode != 0 && mode != 1))
                    return@Around chain!!.proceed()
                if (motion != null) motion!!.returnToNative()
                val view: View? = chain!!.getThisObject() as View?
                val steady: Boolean =
                    mode == 1 && "SUPER_STEADY" == Reflect.get(view!!, "f").toString()
                val selection = PhotoZoomTiming17.Selection(view!!, mode, steady)
                selecting.set(selection)
                var result: Any?
                try {
                    camera.beginZoomMeasurement()
                    result = chain!!.proceed()
                } finally {
                    selecting.remove()
                }
                // Non-table transitions already use Samsung's own eased ValueAnimator.
                if (selection.runner != null) {
                    try {
                        val next = Motion(selection)
                        (Reflect.get(selection.view, "I") as Handler).removeCallbacks(
                            selection!!.runner!!
                        )
                        runtime.guard.arm()
                        selection.generation++
                        Reflect.field(selection.view.javaClass, "l")!!.setInt(
                            selection.view,
                            selection.generation.toInt(),
                        )
                        motion = next
                        next.start()
                    } catch (error: ReflectiveOperationException) {
                        ModernHook.log("Photo zoom: retaining native timing: " + error)
                    } catch (error: IllegalArgumentException) {
                        ModernHook.log("Photo zoom: retaining native timing: " + error)
                    }
                }
                result
            },
        )
        ModernHook.findAndHookMethod(
            zoomView,
            "clear",
            object : ModernHook.Callback() {
                override fun beforeHookedMethod(parameter: ModernHook.Param) {
                    if (motion != null && motion!!.selection.view === parameter.thisObject)
                        motion!!.stop()
                }
            },
        )
        runtime.onChanged(
            Runnable {
                if (!runtime.enabled(ConfigProvider.LENS_MOTION)) {
                    if (motion != null) motion!!.returnToNative()
                    val current = nativeAnimator.get()
                    if (current != null && current.isRunning()) current.end()
                } else if (
                    !runtime.enabled(ConfigProvider.FPS) &&
                        motion != null &&
                        motion!!.display != null
                ) {
                    motion!!.returnToNative()
                }
            }
        )
    }

    private class Selection(view: View, mode: Int, steady: Boolean) {
        @JvmField val view: View
        @JvmField val mode: Int
        @JvmField val steady: Boolean
        @JvmField var runner: Runnable? = null
        @JvmField var points: ArrayList<*>? = null
        @JvmField var generation: Long = 0

        init {
            this.view = view
            this.mode = mode
            this.steady = steady
        }
    }

    private inner class Motion(selection: PhotoZoomTiming17.Selection) : FrameCallback {
        @JvmField val selection: PhotoZoomTiming17.Selection
        @JvmField val curve: MutableList<ZoomCurve.Point?> = ArrayList<ZoomCurve.Point?>()
        @JvmField val settings: Any
        @JvmField val nativeHandler: Handler
        @JvmField val previousFrameRate: Float
        @JvmField val duration: Int
        @JvmField val nativeDuration: Int
        @JvmField val generationField: Field
        @JvmField val runningField: Field
        @JvmField val writeZoom: Method
        @JvmField val updateButtons: Method
        @JvmField val updateLabel: Method
        @JvmField val requests: ZoomRequestQueue
        @JvmField var started: Long = 0
        @JvmField var sampledAt: Long = 0
        @JvmField var lastZoom: Int = Int.MIN_VALUE
        @JvmField var frames: Int = 0
        @JvmField @Volatile var stopped: Boolean = false
        @JvmField @Volatile var display: PhotoPreviewTransition17? = null
        @JvmField @Volatile var appliedZoom: Int = Int.MIN_VALUE

        init {
            this.selection = selection
            for (point in selection.points!!) {
                curve.add(
                    ZoomCurve.Point(
                        Reflect.get(point, "a") as Float,
                        Reflect.get(point, "b") as Int,
                    )
                )
            }
            nativeDuration = ZoomCurve.duration(curve)
            duration = LensMotionTiming.duration(nativeDuration)
            require(!(nativeDuration <= 0 || nativeDuration > 3000)) { "Unknown curve duration" }
            settings = (Reflect.get(Reflect.get(selection.view, "b")!!, "d"))!!
            nativeHandler = (Reflect.get(selection.view, "I") as Handler?)!!
            generationField = (Reflect.field(selection.view.javaClass, "l"))!!
            runningField = (Reflect.field(selection.view.javaClass, "p"))!!
            writeZoom =
                (Reflect.method(
                    settings.javaClass,
                    "V",
                    Int::class.javaPrimitiveType,
                    settingKey,
                ))!!
            updateButtons =
                (Reflect.method(selection.view.javaClass, "C2", Int::class.javaPrimitiveType))!!
            updateLabel =
                (Reflect.method(selection.view.javaClass, "N2", Int::class.javaPrimitiveType))!!
            previousFrameRate = selection.view.getRequestedFrameRate()
            requests =
                ZoomRequestQueue(
                    Executor { r: Runnable? -> nativeHandler.post(r!!) },
                    ZoomRequestQueue.Writer { value: Int ->
                        if (value != appliedZoom) {
                            writeZoom.invoke(settings, value, zoomKey)
                            appliedZoom = value
                        }
                    },
                    Runnable { runtime.main.post(Runnable { this.complete() }) },
                    Consumer { error: Exception? ->
                        runtime.main.post(
                            Runnable {
                                if (stopped) return@Runnable
                                returnToNative()
                                runtime.fail(error!!)
                            }
                        )
                    },
                )
        }

        fun start() {
            if (
                selection.mode == 0 &&
                    runtime.enabled(ConfigProvider.FPS) &&
                    camera.highRatePreviewActive() &&
                    camera.rearCamera()
            ) {
                try {
                    display =
                        PhotoPreviewTransition17(
                            runtime,
                            camera.previewSurface(),
                            curve.get(0)!!.zoom,
                            curve.get(curve.size - 1)!!.zoom,
                            Runnable {
                                requests.submit(
                                    Math.round(curve.get(curve.size - 1)!!.zoom * 1000),
                                    true,
                                )
                            },
                        )
                    display!!.capture(
                        Runnable {
                            if (stopped) return@Runnable
                            requests.submit(
                                Math.round(curve.get(curve.size - 1)!!.zoom * 1000),
                                false,
                            )
                            startFrames()
                        },
                        Runnable {
                            if (stopped) return@Runnable
                            display = null
                            startFrames()
                        },
                    )
                    return
                } catch (unavailable: IllegalArgumentException) {
                    display = null
                    ModernHook.log("Photo display handoff: native fallback")
                } catch (unavailable: ReflectiveOperationException) {
                    display = null
                    ModernHook.log("Photo display handoff: native fallback")
                }
            }
            startFrames()
        }

        fun startFrames() {
            started = System.nanoTime()
            selection.view.setRequestedFrameRate(60f)
            Choreographer.getInstance().postFrameCallback(this)
            ModernHook.log(
                ("Lens motion: eased curve, display synchronized; mode=" +
                    selection.mode +
                    "; steady=" +
                    selection.steady +
                    "; duration=" +
                    duration +
                    "; startNanos=" +
                    started)
            )
        }

        override fun doFrame(frameTimeNanos: Long) {
            if (stopped) return
            try {
                if (
                    generationField.getInt(selection.view).toLong() != selection.generation ||
                        !runningField.getBoolean(selection.view)
                ) {
                    stop()
                    return
                }
                if (
                    !runtime.enabled(ConfigProvider.LENS_MOTION) ||
                        camera.mode() != selection.mode ||
                        (display != null && !camera.highRatePreviewActive())
                ) {
                    returnToNative()
                    return
                }
                val elapsed = (max(0, (frameTimeNanos - started) / 1000000)).toInt()
                if (
                    sampledAt != 0L && frameTimeNanos - sampledAt < 16000000L && elapsed < duration
                ) {
                    Choreographer.getInstance().postFrameCallback(this)
                    return
                }
                sampledAt = frameTimeNanos
                val value =
                    Math.round(
                        ZoomCurve.valueAt(
                            curve,
                            LensMotionTiming.curveTime(elapsed, duration, nativeDuration),
                        ) * 1000
                    )
                if (value != lastZoom) {
                    updateButtons.invoke(selection.view, value)
                    updateLabel.invoke(selection.view, value)
                    lastZoom = value
                    frames++
                    if (display == null) requests.submit(value, elapsed >= duration)
                } else if (elapsed >= duration && display == null) {
                    requests.submit(value, true)
                }
                if (display != null) display!!.sample(value / 1000f, elapsed >= duration)
                if (elapsed < duration) {
                    Choreographer.getInstance().postFrameCallback(this)
                }
            } catch (error: Throwable) {
                returnToNative()
                runtime.fail(error)
            }
        }

        fun complete() {
            if (stopped) return
            try {
                if (
                    generationField.getInt(selection.view).toLong() != selection.generation ||
                        !runningField.getBoolean(selection.view)
                ) {
                    stop()
                    return
                }
                stop()
                (Reflect.get(selection.view, "L") as Handler).post(
                    Reflect.get(selection.view, "S") as Runnable
                )
                ModernHook.log("Lens motion: completed; display samples=" + frames)
            } catch (error: ReflectiveOperationException) {
                returnToNative()
                runtime.fail(error)
            }
        }

        fun returnToNative() {
            if (stopped) return
            val finalLensRequested: Boolean = display != null && appliedZoom != Int.MIN_VALUE
            stop()
            try {
                if (
                    generationField.getInt(selection.view).toLong() != selection.generation ||
                        !runningField.getBoolean(selection.view)
                )
                    return
                if (finalLensRequested) {
                    // The camera already uses the target lens. Do not replay earlier zoom requests
                    // on OFF.
                    (Reflect.get(selection.view, "S") as Runnable).run()
                    return
                }
                val elapsed: Int =
                    if (started == 0L) 0
                    else (max(0, (System.nanoTime() - started) / 1000000)).toInt()
                val index =
                    ZoomCurve.nextIndex(
                        curve,
                        LensMotionTiming.curveTime(elapsed, duration, nativeDuration),
                    )
                val continuation: Runnable? =
                    nativeStep!!.newInstance(
                        selection.view,
                        selection.generation,
                        selection.points,
                        index,
                    ) as Runnable?
                nativeHandler.post(continuation!!)
            } catch (error: Throwable) {
                runtime.fail(error)
            }
        }

        fun stop() {
            stopped = true
            if (display != null) display!!.close()
            requests.cancel()
            Choreographer.getInstance().removeFrameCallback(this)
            selection.view.setRequestedFrameRate(previousFrameRate)
            if (motion == this) motion = null
        }
    }
}
