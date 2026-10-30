package dev.ryuya.galaxycamera

import android.app.Activity
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureRequest.Builder
import android.hardware.camera2.CaptureRequest.Key
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Bundle
import android.os.Handler
import android.os.PowerManager
import android.os.PowerManager.OnThermalStatusChangedListener
import android.os.SystemClock
import android.util.Pair
import android.util.Range
import android.util.Size
import android.view.SurfaceView
import io.github.libxposed.api.XposedInterface.Chain
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Arrays
import java.util.Collections
import java.util.HashMap
import java.util.List
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.function.BiConsumer
import java.util.function.Consumer
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmRecord
import kotlin.jvm.Synchronized
import kotlin.math.log
import kotlin.math.max

/* Samsung Camera 17.0.00.43 / BZI9 only. Never shared with an Android 16 contract. */
class CameraHooks17(runtime: HostRuntime) {
    private val rt: HostRuntime
    @Volatile private var activity: WeakReference<Activity?> = WeakReference<Activity?>(null)

    @JvmRecord
    private data class SavedPreviewFps(
        val global: Range<Int>?,
        val physical: MutableMap<String?, Range<Int>?>?,
    ) {
        override fun toString(): String = "SavedPreviewFps[global=$global, physical=$physical]"
    }

    private val previewOverrides: MutableMap<Builder?, CameraHooks17.SavedPreviewFps?>? =
        Collections.synchronizedMap<Builder?, CameraHooks17.SavedPreviewFps?>(
            WeakHashMap<Builder?, CameraHooks17.SavedPreviewFps?>()
        )
    private val previewSupport: MutableMap<String?, MutableMap<Size?, Boolean?>?> =
        ConcurrentHashMap<String?, MutableMap<Size?, Boolean?>?>()
    @Volatile private var thermalStatus: Int = 0
    private var nextStatisticsRead: Long = 0
    @Volatile private var focusSamples: BiConsumer<Any?, TotalCaptureResult?>? = null
    @Volatile private var zoomSamples: Consumer<TotalCaptureResult?>? = null
    private val physicalFpsSupport: MutableMap<String?, Boolean?> =
        ConcurrentHashMap<String?, Boolean?>()
    private var reportedCameraId: String? = null
    private var reportedPreviewSize: Size? = null
    @Volatile private var reportedHeat: Int = -1
    @Volatile private var reportedHighRate: Boolean = false
    private val capabilities: MutableMap<String?, CameraCharacteristics?> =
        ConcurrentHashMap<String?, CameraCharacteristics?>()
    private var settingsKey: Class<*>? = null
    private var cameraSettings: Field? = null
    private var readMode: Method? = null
    private var modeKey: Any? = null
    private var stream: String = ""
    private var zoomResultRange: Range<Int>? = null
    private var zoomExposure: Long? = null
    private var firstTimestamp: Long = 0
    private var lastTimestamp: Long = 0
    private var firstFrame: Long = 0
    private var samples: Int = 0
    private var zoomUntil: Long = 0
    private var zoomLastTimestamp: Long = 0
    private var zoomIntervals: Long = 0
    private var zoomTotalNanos: Long = 0
    private var zoomSlowIntervals: Long = 0
    private var zoomRequest: Range<Int>? = null
    private var zoomHeat: Int? = null

    init {
        rt = runtime
    }

    @Throws(Exception::class)
    fun install() {
        val l = rt.loader
        val camera = Class.forName("com.sec.android.app.camera.Camera", false, l)
        settingsKey = Class.forName("l9.f0", false, l)
        modeKey = Reflect.value(settingsKey, "MODE_CUSTOM_SETTING")
        cameraSettings = Reflect.field(camera, "j")
        readMode = Reflect.method(Class.forName("vc.j", false, l), "x", settingsKey)
        ModernHook.findAndHookMethod(
            camera,
            "onCreate",
            Bundle::class.java,
            object : ModernHook.Callback() {
                override fun beforeHookedMethod(p: ModernHook.Param) {
                    activity = WeakReference<Activity?>(p.thisObject as Activity?)
                }
            },
        )
        val maker = Class.forName("com.samsung.android.camera.core2.maker.MakerBase", false, l)
        val power = rt.context.getSystemService<PowerManager?>(PowerManager::class.java)
        thermalStatus = power.getCurrentThermalStatus()
        power.addThermalStatusListener(
            rt.context.getMainExecutor(),
            OnThermalStatusChangedListener { status: Int -> thermalStatus = status },
        )
        for (method in
            List.of<Method?>(
                Reflect.method(maker, "setPublicSetting", Key::class.java, Any::class.java),
                Reflect.method(maker, "setSessionKeys", MutableMap::class.java),
            )) {
            ModernHook.hookMethod(
                method,
                object : ModernHook.Callback() {
                    override fun afterHookedMethod(call: ModernHook.Param) {
                        if (call.hasThrowable()) return
                        val fpsChange: Boolean =
                            if (call.args.size == 2)
                                (CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE == call.args[0])
                            else
                                (call.args[0] as MutableMap<*, *>).containsKey(
                                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE
                                )
                        if (fpsChange) applyPreviewFps(call.thisObject)
                    }
                },
            )
        }
        // Patch the actual preview builders, including paths whose vendor helpers were inlined.
        // No global Builder.set hook and no changes to the picture/record request maps.
        ModernHook.findAndHookMethod(
            maker,
            "applySettings",
            object : ModernHook.Callback() {
                override fun beforeHookedMethod(call: ModernHook.Param) {
                    applyPreviewFps(call.thisObject)
                }
            },
        )
        val photoMaker =
            Class.forName("com.samsung.android.camera.core2.maker.PhotoMakerBase", false, l)
        ModernHook.findAndHookMethod(
            photoMaker,
            "startPreviewRepeating",
            object : ModernHook.Callback() {
                override fun beforeHookedMethod(call: ModernHook.Param) {
                    applyPreviewFps(call.thisObject)
                }
            },
        )
        // These small calls can be inlined into connectCamDevice before the first session exists.
        // Deoptimize only the setup method, never the repeating request or image-processing loop.
        ModernHook.deoptimize(
            Reflect.method(
                photoMaker,
                "connectCamDevice",
                Class.forName("com.samsung.android.camera.core2.CamDevice", false, l),
                Class.forName(
                    "com.samsung.android.camera.core2.container.DeviceConfiguration",
                    false,
                    l,
                ),
                Class.forName(
                    "com.samsung.android.camera.core2.callback.MakerStateCallback",
                    false,
                    l,
                ),
                Handler::class.java,
            )!!
        )
        ModernHook.findAndHookMethod(
            photoMaker,
            "createBuilderConfig",
            object : ModernHook.Callback() {
                override fun beforeHookedMethod(call: ModernHook.Param) {
                    applyPreviewFps(call.thisObject)
                    if (rt.enabled(ConfigProvider.FPS) && mode() == 0)
                        ModernHook.log("Photo initial preview FPS prepared")
                }
            },
        )
        val callback =
            Class.forName("com.samsung.android.camera.core2.maker.PhotoMakerBase$6", false, l)
        val cap = Class.forName("com.samsung.android.camera.core2.CamCapability", false, l)
        ModernHook.aroundMethod(
            Reflect.method(
                callback,
                "onPreviewCaptureResult",
                TotalCaptureResult::class.java,
                cap,
            )!!,
            ModernHook.Around { chain: Chain? ->
                val value = chain!!.proceed()
                if (rt.guard.allowed()) {
                    val result: TotalCaptureResult? =
                        chain!!.getArgs().get(0) as TotalCaptureResult?
                    measure(result!!)
                    if (focusSamples != null) focusSamples!!.accept(chain!!.getThisObject(), result)
                    val zoom: Consumer<TotalCaptureResult?>? = zoomSamples
                    if (zoom != null) zoom.accept(result)
                }
                value
            },
        )
        PhotoPreviewStream17(rt, this).install()
        PhotoZoomTiming17(rt, this).install()
        VideoModes17(rt, this).install()
        UltraWideLight17(rt).install()
        FlashFocus17(rt, this).install()
        CameraPanelLight17(rt).install()
        rt.ready()
    }

    fun onPreviewResult(listener: BiConsumer<Any?, TotalCaptureResult?>?) {
        focusSamples = listener
    }

    fun onZoomResult(listener: Consumer<TotalCaptureResult?>?) {
        zoomSamples = listener
    }

    fun highRatePreviewActive(): Boolean {
        return thermalStatus < 3 && reportedHeat == 0 && reportedHighRate
    }

    @Throws(ReflectiveOperationException::class)
    fun previewSurface(): SurfaceView? {
        val current = activity.get()
        if (current == null) return null
        return Reflect.get(Reflect.get(Reflect.get(current, "f")!!, "g")!!, "d") as SurfaceView?
    }

    fun rearCamera(): Boolean {
        val current = activity.get()
        if (current == null) return false
        try {
            val settings = cameraSettings!!.get(current)
            return settings != null && Reflect.call(settings, "f") as Int == 0
        } catch (error: ReflectiveOperationException) {
            return false
        }
    }

    fun mode(): Int {
        val a = activity.get()
        if (a == null || a.isFinishing()) return Int.MIN_VALUE
        try {
            val settings = cameraSettings!!.get(a)
            return if (settings == null) Int.MIN_VALUE
            else readMode!!.invoke(settings, modeKey) as Int
        } catch (e: ReflectiveOperationException) {
            return Int.MIN_VALUE
        }
    }

    @Throws(ReflectiveOperationException::class)
    fun backVideoResolution(): Int {
        val a = activity.get()
        if (a == null) return Int.MIN_VALUE
        val settings = Reflect.get(a, "j")
        return Reflect.method(settings!!.javaClass, "d", settingsKey)!!.invoke(
            settings,
            Reflect.value(settingsKey, "BACK_CAMCORDER_RESOLUTION"),
        ) as Int
    }

    fun busy(): Boolean {
        try {
            val a = activity.get()
            val engine: Any? = if (a == null) null else Reflect.get(a, "n")
            return engine != null && "IDLE" != Reflect.get(engine, "C").toString()
        } catch (ignored: ReflectiveOperationException) {
            return true
        }
    }

    @Throws(ReflectiveOperationException::class)
    fun restoreNormalVideoSelection(resolution: Class<*>?) {
        if (!rt.available() || rt.configured(ConfigProvider.VIDEO) || !rt.guard.allowed()) return
        val a = activity.get()
        if (a == null) return
        val engine = Reflect.get(a, "n")
        if (engine == null || "IDLE" != Reflect.get(engine, "C").toString()) return
        val selected =
            Reflect.method(resolution!!, "j", Int::class.javaPrimitiveType)!!.invoke(
                null,
                backVideoResolution(),
            )
        val name = selected.toString()
        if (
            !java.util.Set.of<String?>("RESOLUTION_1920X1080_120FPS", "RESOLUTION_3840X2160_120FPS")
                .contains(name)
        )
            return
        val fallback = Reflect.value(resolution, name.replace("_120FPS", ""))
        val settings = Reflect.get(a, "j")
        rt.guard.arm()
        Reflect.method(settings!!.javaClass, "V", Int::class.javaPrimitiveType, settingsKey)!!
            .invoke(
                settings,
                Reflect.call(fallback!!, "e"),
                Reflect.value(settingsKey, "BACK_CAMCORDER_RESOLUTION"),
            )
        rt.report("VIDEO: disabled; restored native 30 fps selection")
    }

    private fun applyPreviewFps(maker: Any?) {
        if (!rt.enabled(ConfigProvider.FPS) || mode() != 0) return
        try {
            val type: Int = Reflect.call(maker!!, "getMakerType") as Int
            val shooting: Int = Reflect.call(maker!!, "getMakerShootingMode") as Int
            if (type != 1 || (shooting != 1 && shooting != 2)) return
            val device = Reflect.get(maker!!, "mCamDevice")
            if (device == null) return
            val id: String? = Reflect.call(device, "m") as String?
            val size: Size? = Reflect.get(maker!!, "mMainPreviewSurfaceSize") as Size?
            if (size == null) return
            val sizes =
                previewSupport.computeIfAbsent(id) { ignored: String? ->
                    ConcurrentHashMap<Size?, Boolean?>()
                }
            var supported = sizes!!.get(size)
            var characteristics = capabilities.get(id)
            if (characteristics == null) {
                characteristics =
                    rt.context
                        .getSystemService<CameraManager?>(CameraManager::class.java)
                        .getCameraCharacteristics(id!!)
                capabilities.put(id, characteristics)
            }
            // Cache immutable stream capabilities; live Android/Samsung thermal limits are checked
            // below.
            if (supported == null) {
                val ranges =
                    characteristics.get<Array<Range<Int>?>?>(
                        CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
                    )
                val outputs =
                    characteristics.get<StreamConfigurationMap?>(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
                    )
                val minimum: Long =
                    if (outputs == null) 0
                    else
                        outputs.getOutputMinFrameDuration<SurfaceTexture?>(
                            SurfaceTexture::class.java,
                            size,
                        )
                supported =
                    PreviewPolicy.eligible(
                        true,
                        0,
                        type,
                        ranges != null &&
                            Arrays.asList<Range<Int>?>(*ranges).contains(CameraHooks17.PREVIEW_60),
                        minimum,
                        0,
                    )
                sizes!!.put(size, supported)
            }
            if (!supported) return
            var physicalFps = physicalFpsSupport.get(id)
            if (physicalFps == null) {
                val keys = characteristics.getAvailablePhysicalCameraRequestKeys()
                physicalFps =
                    keys != null && keys.contains(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE)
                physicalFpsSupport.put(id, physicalFps)
            }
            val builders: MutableMap<*, *>? =
                Reflect.get(maker!!, "mPreviewRequestBuilderMap") as MutableMap<*, *>?
            var highRate: Boolean = false
            var cameraHeat: Int = 0
            var armed: Boolean = false
            for (item in builders!!.entries) {
                val builder = item.value as? Builder
                if (builder == null) continue
                val heat = builder.get<Int?>(CameraHooks17.CAMERA_HEAT)
                cameraHeat = max(cameraHeat, if (heat == null) 0 else heat)
                if (thermalStatus >= 3 || (heat != null && heat > 0)) {
                    val saved = previewOverrides!!.remove(builder)
                    if (saved != null) {
                        if (!armed) {
                            rt.guard.arm()
                            armed = true
                        }
                        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, saved.global)
                        for (value in saved.physical!!.entries) builder.setPhysicalCameraKey(
                            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                            value.value,
                            value.key!!,
                        )
                    }
                    continue
                }
                highRate = true
                if (!armed) {
                    rt.guard.arm()
                    armed = true
                }
                val current = builder.get<Range<Int>?>(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE)
                if (
                    !previewOverrides!!.containsKey(builder) || CameraHooks17.PREVIEW_60 != current
                ) {
                    val originals = HashMap<String?, Range<Int>?>()
                    run {
                        val patternPrefix0 = physicalFps
                        val pair = if (patternPrefix0) item.key as? Pair<*, *> else null
                        if (patternPrefix0 && pair != null && pair.second is MutableSet<*>) {
                            for (physicalId in (pair.second as Set<*>)) {
                                val physicalIdString: String? = physicalId as String?
                                originals.put(
                                    physicalIdString!!,
                                    builder.getPhysicalCameraKey<Range<Int>?>(
                                        CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                        physicalIdString!!,
                                    ),
                                )
                            }
                        }
                    }
                    previewOverrides!!.put(
                        builder,
                        CameraHooks17.SavedPreviewFps(current, originals),
                    )
                }
                if (CameraHooks17.PREVIEW_60 != current)
                    builder.set<Range<Int>?>(
                        CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                        CameraHooks17.PREVIEW_60,
                    )
                run {
                    val patternPrefix1 = physicalFps
                    val pair = if (patternPrefix1) item.key as? Pair<*, *> else null
                    if (patternPrefix1 && pair != null && pair.second is MutableSet<*>) {
                        for (physicalId in (pair.second as Set<*>)) builder.setPhysicalCameraKey<
                            Range<Int>?
                        >(
                            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                            CameraHooks17.PREVIEW_60,
                            physicalId as String,
                        )
                    }
                }
            }
            if (
                (id != reportedCameraId) ||
                    (size != reportedPreviewSize) ||
                    reportedHeat != cameraHeat ||
                    reportedHighRate != highRate
            ) {
                reportedCameraId = id
                reportedPreviewSize = size
                reportedHeat = cameraHeat
                reportedHighRate = highRate
                stream = "id=" + id + "; preview=" + size
                rt.report(
                    ((if (highRate) "PHOTO: requested 60 fps; "
                    else "PHOTO: native fps; thermal fallback; ") +
                        "cameraHeat=" +
                        cameraHeat +
                        "; " +
                        stream)
                )
            }
        } catch (unsupported: IllegalArgumentException) {
            // A stale or unsupported stream keeps the native setup, without poisoning the host.
            ModernHook.log(
                "Photo preview: retaining native stream: " + unsupported.javaClass.getSimpleName()
            )
        } catch (error: ReflectiveOperationException) {
            rt.fail(error)
        } catch (error: CameraAccessException) {
            rt.fail(error)
        }
    }

    @Synchronized
    fun beginZoomMeasurement() {
        zoomUntil = SystemClock.uptimeMillis() + 1500
        zoomSlowIntervals = 0
        zoomTotalNanos = zoomSlowIntervals
        zoomIntervals = zoomTotalNanos
        zoomLastTimestamp = zoomIntervals
        zoomRequest = null
        zoomResultRange = null
        zoomExposure = null
        zoomHeat = null
    }

    @Synchronized
    private fun measureZoom(result: TotalCaptureResult, timestamp: Long) {
        if (zoomUntil == 0L) return
        if (SystemClock.uptimeMillis() >= zoomUntil) {
            if (zoomIntervals > 0)
                ModernHook.log(
                    String.format(
                        Locale.ROOT,
                        "Photo zoom preview: %.1f fps; intervals=%d; over25ms=%d; requested=%s; result=%s;" +
                            " exposure=%s; cameraHeat=%s",
                        zoomIntervals * 1000000000.0 / zoomTotalNanos,
                        zoomIntervals,
                        zoomSlowIntervals,
                        zoomRequest,
                        zoomResultRange,
                        zoomExposure,
                        zoomHeat,
                    )
                )
            zoomUntil = 0
            return
        }
        val interval: Long = timestamp - zoomLastTimestamp
        if (zoomLastTimestamp > 0 && interval > 0 && interval < 100000000L) {
            zoomIntervals++
            zoomTotalNanos += interval
            if (interval > 25000000L) zoomSlowIntervals++
        }
        zoomLastTimestamp = timestamp
        zoomRequest =
            result.getRequest().get<Range<Int>?>(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE)
        zoomResultRange = result.get<Range<Int>?>(CaptureResult.CONTROL_AE_TARGET_FPS_RANGE)
        zoomExposure = result.get<Long?>(CaptureResult.SENSOR_EXPOSURE_TIME)
        zoomHeat = result.getRequest().get<Int?>(CameraHooks17.CAMERA_HEAT)
    }

    private fun measure(result: TotalCaptureResult) {
        samples++
        val now = SystemClock.uptimeMillis()
        if (zoomUntil == 0L && now < nextStatisticsRead) return
        nextStatisticsRead = now + 200
        val timestamp = result.get<Long?>(CaptureResult.SENSOR_TIMESTAMP)
        if (timestamp == null) return
        measureZoom(result, timestamp)
        if (firstTimestamp == 0L || timestamp <= lastTimestamp) {
            firstTimestamp = timestamp
            firstFrame = result.getFrameNumber()
            samples = 0
        }
        lastTimestamp = timestamp
        val elapsed: Long = timestamp - firstTimestamp
        if (elapsed >= 5000000000L && samples > 1 && mode() == 0) {
            val fps: Double = (result.getFrameNumber() - firstFrame) * 1000000000.0 / elapsed
            val callbacks: Double = (samples - 1) * 1000000000.0 / elapsed
            val duration = result.get<Long?>(CaptureResult.SENSOR_FRAME_DURATION)
            val requested =
                result.getRequest().get<Range<Int>?>(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE)
            rt.report(
                String.format(
                    Locale.ROOT,
                    "PHOTO: %.1f fps; callbacks=%.1f; frameDuration=%s; request=%s; enabled=%s;" +
                        " cameraHeat=%s; %s",
                    fps,
                    callbacks,
                    duration,
                    requested,
                    rt.enabled(ConfigProvider.FPS),
                    result.getRequest().get<Int?>(CameraHooks17.CAMERA_HEAT),
                    stream,
                )
            )
            firstTimestamp = timestamp
            firstFrame = result.getFrameNumber()
            samples = 1
        }
    }

    companion object {
        private val PREVIEW_60: Range<Int> = Range<Int>(60, 60)
        private val CAMERA_HEAT: Key<Int?> =
            Key<Int?>("samsung.android.control.overHeatHint", Int::class.java)
    }
}
