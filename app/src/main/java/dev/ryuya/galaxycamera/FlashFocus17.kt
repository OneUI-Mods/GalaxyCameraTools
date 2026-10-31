package dev.ryuya.galaxycamera

import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.SystemClock
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.function.BiConsumer
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmRecord
import kotlin.jvm.Synchronized
import kotlin.math.log
import kotlin.math.max
import kotlin.math.min

/* Holds the measured lens position for a flash photo, without changing saved focus settings. */
class FlashFocus17(runtime: HostRuntime, hooks: CameraHooks17) {
    private val runtime: HostRuntime
    private val camera: CameraHooks17
    @Volatile private var sample: FlashFocus17.FocusSample? = null
    @Volatile private var hold: FlashFocus17.FocusHold? = null
    private var setPublicSetting: Method? = null
    private var applySettings: Method? = null
    private var nativeLensPosition: android.hardware.camera2.CaptureRequest.Key<Int?>? = null
    private val nativeLensPositionResult: android.hardware.camera2.CaptureResult.Key<Int?> =
        android.hardware.camera2.CaptureResult.Key<Int?>(
            "samsung.android.lens.focusLensPos",
            CameraMetadataTypes.integer,
        )
    private val nativeLensInfo: android.hardware.camera2.CaptureResult.Key<IntArray?> =
        android.hardware.camera2.CaptureResult.Key<IntArray?>(
            "samsung.android.lens.info.currentInfo",
            IntArray::class.java,
        )
    @Volatile private var minimumHeldPosition: Int = 0
    @Volatile private var maximumHeldPosition: Int = 0
    @Volatile private var minimumHeldDistance: Float = 0f
    @Volatile private var maximumHeldDistance: Float = 0f
    @Volatile private var heldResultCount: Int = 0
    private var nextFocusSample: Long = 0
    private var callbackMaker: Field? = null
    private var sampledMaker: WeakReference<Any?> = WeakReference<Any?>(null)
    private val timeout: Runnable = Runnable { release("timeout") }

    @JvmRecord
    private data class FocusSample(
        val maker: WeakReference<Any?>?,
        val distance: Float,
        val lensPosition: Int,
        val sampledAtMillis: Long,
    ) {
        override fun toString(): String =
            "FocusSample[maker=$maker, distance=$distance, lensPosition=$lensPosition, sampledAtMillis=$sampledAtMillis]"
    }

    @JvmRecord
    private data class FocusHold(
        val maker: Any?,
        val engine: Any?,
        val previousAfMode: Any?,
        val distance: Any?,
        val previousLensPosition: Any?,
        val lockedDistance: Float,
        val lockedLensPosition: Int,
    ) {
        override fun toString(): String =
            "FocusHold[maker=$maker, engine=$engine, previousAfMode=$previousAfMode, distance=$distance, previousLensPosition=$previousLensPosition, lockedDistance=$lockedDistance, lockedLensPosition=$lockedLensPosition]"
    }

    init {
        this.runtime = runtime
        camera = hooks
    }

    @Throws(Exception::class)
    fun install() {
        val loader = runtime.loader
        val makerClass =
            Class.forName("com.samsung.android.camera.core2.maker.MakerBase", false, loader)
        val photoMakerClass =
            Class.forName("com.samsung.android.camera.core2.maker.PhotoMakerBase", false, loader)
        val engineClass = Class.forName("p8.e0", false, loader)
        setPublicSetting =
            Reflect.method(
                makerClass,
                "setPublicSetting",
                android.hardware.camera2.CaptureRequest.Key::class.java,
                Any::class.java,
            )
        applySettings = Reflect.method(makerClass, "applySettings")
        nativeLensPosition =
            Reflect.get(
                Class.forName(
                    "com.samsung.android.camera.core2.local.vendorkey.SemCaptureRequest",
                    false,
                    loader,
                ),
                "E0",
            ) as android.hardware.camera2.CaptureRequest.Key<Int?>?
        ModernHook.hookMethod(
            setPublicSetting!!,
            object : ModernHook.Callback() {
                override fun beforeHookedMethod(call: ModernHook.Param) {
                    val current: FlashFocus17.FocusHold? = hold
                    if (current == null || current.maker !== call.thisObject) return
                    if (CaptureRequest.CONTROL_AF_MODE == call.args[0])
                        call.args[1] = CaptureRequest.CONTROL_AF_MODE_OFF
                    else if (CaptureRequest.LENS_FOCUS_DISTANCE == call.args[0])
                        call.args[1] = current.lockedDistance
                    else if (nativeLensPosition == call.args[0])
                        call.args[1] = current.lockedLensPosition
                }
            },
        )
        val previewCallbackClass =
            Class.forName("com.samsung.android.camera.core2.maker.PhotoMakerBase$6", false, loader)
        callbackMaker = Reflect.field(previewCallbackClass, "this$0")
        // Ten metadata samples per second keep the sample fresher than the 350 ms flash limit.
        // No images or camera content leave this process.
        camera.onPreviewResult(
            BiConsumer { callback: Any?, result: TotalCaptureResult ->
                this.sampleFocus(callback, result)
            }
        )
        ModernHook.findAndHookMethod(
            Class.forName("p8.p", false, loader),
            "J",
            Boolean::class.javaPrimitiveType,
            Class.forName("q8.l", false, loader),
            object : ModernHook.Callback() {
                override fun beforeHookedMethod(call: ModernHook.Param) {
                    if (
                        !runtime.enabled(ConfigProvider.FOCUS) ||
                            !(call.args[0] as Boolean) ||
                            camera.mode() != 0 ||
                            hold != null
                    )
                        return
                    try {
                        val latest: FlashFocus17.FocusSample? = sample
                        if (
                            latest == null ||
                                SystemClock.elapsedRealtime() - latest.sampledAtMillis > 350
                        )
                            return
                        val currentMaker = latest.maker!!.get()
                        if (currentMaker == null) return
                        val flashType = Class.forName("l9.k2", false, loader)
                        val condition: Int = Reflect.call(call.args[1]!!, "f") as Int
                        val flashRequired: Boolean =
                            Reflect.method(
                                    call.thisObject!!.javaClass,
                                    "h",
                                    flashType,
                                    Int::class.javaPrimitiveType,
                                )!!
                                .invoke(
                                    call.thisObject,
                                    Reflect.value(flashType, "PHOTO_FLASH"),
                                    condition,
                                ) as Boolean
                        if (!flashRequired) return
                        val previousAfMode =
                            Reflect.method(
                                    photoMakerClass,
                                    "getPublicSetting",
                                    android.hardware.camera2.CaptureRequest.Key::class.java,
                                )!!
                                .invoke(currentMaker, CaptureRequest.CONTROL_AF_MODE)
                        val distance =
                            Reflect.method(
                                    photoMakerClass,
                                    "getPublicSetting",
                                    android.hardware.camera2.CaptureRequest.Key::class.java,
                                )!!
                                .invoke(currentMaker, CaptureRequest.LENS_FOCUS_DISTANCE)
                        if (
                            previousAfMode !is Int ||
                                previousAfMode as Int == CaptureRequest.CONTROL_AF_MODE_OFF
                        )
                            return
                        val previousLensPosition =
                            Reflect.method(
                                    photoMakerClass,
                                    "getPublicSetting",
                                    android.hardware.camera2.CaptureRequest.Key::class.java,
                                )!!
                                .invoke(currentMaker, nativeLensPosition)
                        runtime.guard.arm()
                        hold =
                            FlashFocus17.FocusHold(
                                currentMaker,
                                Reflect.get(call.thisObject!!, "e"),
                                previousAfMode,
                                distance,
                                previousLensPosition,
                                latest.distance,
                                latest.lensPosition,
                            )
                        maximumHeldDistance = latest.distance
                        minimumHeldDistance = maximumHeldDistance
                        maximumHeldPosition = latest.lensPosition
                        minimumHeldPosition = maximumHeldPosition
                        heldResultCount = 0
                        runtime.main.postDelayed(timeout, 8000)
                        setPublicSetting!!.invoke(
                            currentMaker,
                            CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_OFF,
                        )
                        setPublicSetting!!.invoke(
                            currentMaker,
                            CaptureRequest.LENS_FOCUS_DISTANCE,
                            latest.distance,
                        )
                        setPublicSetting!!.invoke(
                            currentMaker,
                            nativeLensPosition,
                            latest.lensPosition,
                        )
                        applySettings!!.invoke(currentMaker)
                        runtime.report("FLASH: focus held at " + latest.distance + " diopters")
                        ModernHook.log(
                            ("Flash focus held at " +
                                latest.distance +
                                "; native position=" +
                                latest.lensPosition)
                        )
                    } catch (error: Throwable) {
                        release("error")
                        runtime.fail(error)
                    }
                }

                override fun afterHookedMethod(call: ModernHook.Param) {
                    if (call.hasThrowable()) release("cancelled")
                }
            },
        )
        // Samsung's native pre-capture sequence then waits for AE only, not an AF scan
        // that cannot finish with AF disabled. Exposure metering and flash timing remain native.
        ModernHook.findAndHookMethod(
            engineClass,
            "U",
            object : ModernHook.Callback() {
                override fun afterHookedMethod(call: ModernHook.Param) {
                    val current: FlashFocus17.FocusHold? = hold
                    if (
                        !call.hasThrowable() &&
                            current != null &&
                            current.engine === call.thisObject
                    )
                        call.setResult(false)
                }
            },
        )
        ModernHook.findAndHookMethod(
            engineClass,
            "f",
            Class.forName("z8.m0", false, loader),
            object : ModernHook.Callback() {
                override fun afterHookedMethod(call: ModernHook.Param) {
                    val current: FlashFocus17.FocusHold? = hold
                    if (
                        !call.hasThrowable() &&
                            current != null &&
                            current.engine === call.thisObject &&
                            "IDLE" == call.args[0].toString()
                    )
                        release("complete")
                }
            },
        )
        ModernHook.findAndHookMethod(
            Class.forName("com.sec.android.app.camera.Camera", false, loader),
            "onPause",
            object : ModernHook.Callback() {
                override fun beforeHookedMethod(call: ModernHook.Param) {
                    release("camera paused")
                    sample = null
                }
            },
        )
        runtime.onChanged(
            Runnable {
                if (!runtime.enabled(ConfigProvider.FOCUS)) release("disabled")
            }
        )
    }

    private fun sampleFocus(callback: Any?, result: TotalCaptureResult) {
        if (!runtime.enabled(ConfigProvider.FOCUS)) return
        val now = SystemClock.elapsedRealtime()
        if (now < nextFocusSample) return
        nextFocusSample = now + 100
        if (hold != null) {
            val mode = result.get<Int?>(CaptureResult.CONTROL_AF_MODE)
            if (mode == null || mode != CaptureResult.CONTROL_AF_MODE_OFF) return
            val position = measuredLensPosition(result)
            if (position != null) {
                minimumHeldPosition = min(minimumHeldPosition, (position).toInt())
                maximumHeldPosition = max(maximumHeldPosition, (position).toInt())
            }
            val measuredDistance = result.get<Float?>(CaptureResult.LENS_FOCUS_DISTANCE)
            if (measuredDistance != null) {
                minimumHeldDistance = min(minimumHeldDistance, (measuredDistance).toFloat())
                maximumHeldDistance = max(maximumHeldDistance, (measuredDistance).toFloat())
                heldResultCount++
            }
            return
        }
        try {
            val distance = result.get<Float?>(CaptureResult.LENS_FOCUS_DISTANCE)
            val position = measuredLensPosition(result)
            if (
                distance != null &&
                    position != null &&
                    java.lang.Float.isFinite(distance) &&
                    distance >= 0
            ) {
                val maker = callbackMaker!!.get(callback)
                if (sampledMaker.get() !== maker) sampledMaker = WeakReference<Any?>(maker)
                sample = FlashFocus17.FocusSample(sampledMaker, distance, position, now)
            }
        } catch (error: Throwable) {
            runtime.fail(error)
        }
    }

    private fun measuredLensPosition(result: TotalCaptureResult): Int? {
        // This is the same position source used by Samsung's LensInfoCallback.
        val lensInfo = result.get<IntArray?>(nativeLensInfo)
        if (lensInfo != null && lensInfo.size > 3) return lensInfo[3]
        return result.get<Int?>(nativeLensPositionResult)
    }

    @Synchronized
    private fun release(reason: String?) {
        val previous: FlashFocus17.FocusHold? = hold
        if (previous == null) return
        hold = null
        sample = null
        runtime.main.removeCallbacks(timeout)
        try {
            setPublicSetting!!.invoke(
                previous.maker,
                nativeLensPosition,
                previous.previousLensPosition,
            )
            setPublicSetting!!.invoke(
                previous.maker,
                CaptureRequest.LENS_FOCUS_DISTANCE,
                previous.distance,
            )
            setPublicSetting!!.invoke(
                previous.maker,
                CaptureRequest.CONTROL_AF_MODE,
                previous.previousAfMode,
            )
            applySettings!!.invoke(previous.maker)
            runtime.report("FLASH: autofocus restored (" + reason + ")")
            ModernHook.log(
                ("Flash focus restored: " +
                    reason +
                    "; frames=" +
                    heldResultCount +
                    "; range=" +
                    minimumHeldDistance +
                    ".." +
                    maximumHeldDistance +
                    "; native position=" +
                    minimumHeldPosition +
                    ".." +
                    maximumHeldPosition)
            )
        } catch (error: ReflectiveOperationException) {
            // A disconnect already released this maker; never reopen a camera to restore it.
            ModernHook.log(
                "Flash focus restore after disconnect: " + error.javaClass.getSimpleName()
            )
        }
    }
}
