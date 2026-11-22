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
import dev.ryuya.galaxycamera.compat.DexSymbols
import io.github.libxposed.api.XposedInterface.Chain
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Arrays
import java.util.Collections
import java.util.HashMap
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.function.BiConsumer
import java.util.function.Consumer
import kotlin.concurrent.Volatile
import kotlin.jvm.Synchronized
import kotlin.math.log
import kotlin.math.max

/* Samsung Camera 17.0.00.43 / BZI9 only. Never shared with an Android 16 contract. */
class CameraHooks17(runtime: HostRuntime) {
    private val rt: HostRuntime
    @Volatile private var activity: WeakReference<Activity?> = WeakReference<Activity?>(null)

    private class SavedPreviewFps {
        var ownsGlobal = false
        var global: Range<Int>? = null
        val physical = HashMap<String, Range<Int>?>()
    }

    private val previewOverrides = Collections.synchronizedMap(
        WeakHashMap<Builder, SavedPreviewFps>(),
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
    @Volatile private var sampledHeat: Int = 0
    @Volatile var previewFrameNumber: Long = Long.MIN_VALUE
        private set
    private val capabilities = ConcurrentHashMap<String, CameraCharacteristics>()
    private var settingsKey: Class<*>? = null
    private var cameraSettings: Field? = null
    private var readMode: Method? = null
    private var modeKey: Any? = null
    private var stream: String = ""
    private var firstTimestamp: Long = 0
    private var lastTimestamp: Long = 0
    private var firstFrame: Long = 0
    private var samples: Int = 0
    @Volatile private var zoomUntil: Long = 0
    private var zoomLastTimestamp: Long = 0
    private var zoomIntervals: Long = 0
    private var zoomTotalNanos: Long = 0
    private var zoomSlowIntervals: Long = 0
    private var zoomLastResult: TotalCaptureResult? = null

    init {
        rt = runtime
    }

    @Throws(Exception::class)
    fun install() {
        val l = rt.loader
        val camera = DexSymbols.findClass("com.sec.android.app.camera.Camera", false, l)
        settingsKey = DexSymbols.findClass("l9.f0", false, l)
        modeKey = Reflect.value(settingsKey, "MODE_CUSTOM_SETTING")
        cameraSettings = Reflect.field(camera, "j")
        readMode = Reflect.method(DexSymbols.findClass("vc.j", false, l), "x", settingsKey)
        val rememberActivity = object : ModernHook.Callback() {
            override fun beforeHookedMethod(p: ModernHook.Param) {
                activity = WeakReference(p.thisObject as? Activity)
            }
        }
        ModernHook.findAndHookMethod(camera, "onCreate", Bundle::class.java, rememberActivity)
        ModernHook.findAndHookMethod(camera, "onResume", rememberActivity)
        ModernHook.aroundMethod(camera.getDeclaredMethod("onPause")) { chain ->
            try {
                chain.proceed()
            } finally {
                stopZoomMeasurement()
            }
        }
        rt.onReloadReady {
            val host = activity.get()
            val engine = host?.let { Reflect.get(it, "n") }
            engine == null || Reflect.get(engine, "B").toString() != "PREVIEWING"
        }
        val maker =
            DexSymbols.findClass("com.samsung.android.camera.core2.maker.MakerBase", false, l)
        val power = rt.context.getSystemService<PowerManager?>(PowerManager::class.java)
        thermalStatus = power?.getCurrentThermalStatus() ?: PowerManager.THERMAL_STATUS_SEVERE
        if (power != null) {
            val listener = OnThermalStatusChangedListener { thermalStatus = it }
            power.addThermalStatusListener(rt.context.getMainExecutor(), listener)
            rt.onRetire { power.removeThermalStatusListener(listener) }
        }
        for (method in
            listOfNotNull(
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
                                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
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
            DexSymbols.findClass("com.samsung.android.camera.core2.maker.PhotoMakerBase", false, l)
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
                DexSymbols.findClass("com.samsung.android.camera.core2.CamDevice", false, l),
                DexSymbols.findClass(
                    "com.samsung.android.camera.core2.container.DeviceConfiguration",
                    false,
                    l,
                ),
                DexSymbols.findClass(
                    "com.samsung.android.camera.core2.callback.MakerStateCallback",
                    false,
                    l,
                ),
                Handler::class.java,
            )!!,
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
            DexSymbols.findClass(
                "com.samsung.android.camera.core2.maker.PhotoMakerBase$6",
                false,
                l,
            )
        val cap = DexSymbols.findClass("com.samsung.android.camera.core2.CamCapability", false, l)
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
        runCatching { CameraPauseAudio17(rt).install(camera) }
            .onFailure { ModernHook.log("CAMERA_PAUSE_VOICE_UNAVAILABLE ${it.javaClass.simpleName}") }
        runCatching { CameraResumeReceivers17(rt).install(camera) }
            .onFailure { ModernHook.log("CAMERA_RESUME_RECEIVERS_UNAVAILABLE ${it.javaClass.simpleName}") }
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

    fun thermalZoomLimited(): Boolean =
        thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE || sampledHeat > 0 ||
            (rt.configured(ConfigProvider.FPS) && reportedHeat > 0)

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

    // The stock and lightweight Photo preview paths can ask for the same camera
    // during one startup. Reuse its immutable characteristics across both hooks.
    internal fun cameraCharacteristics(id: String): CameraCharacteristics? {
        capabilities[id]?.let { return it }
        val manager = rt.context.getSystemService(CameraManager::class.java) ?: return null
        return capabilities.computeIfAbsent(id, manager::getCameraCharacteristics)
    }

    private fun applyPreviewFps(maker: Any?) {
        if (!rt.enabled(ConfigProvider.FPS) || mode() != 0) return
        try {
            val type: Int = Reflect.call(maker!!, "getMakerType") as Int
            val shooting: Int = Reflect.call(maker!!, "getMakerShootingMode") as Int
            if (type != 1 || (shooting != 1 && shooting != 2)) return
            val device = Reflect.get(maker!!, "mCamDevice")
            if (device == null) return
            val id = Reflect.call(device, "m") as? String ?: return
            val size: Size? = Reflect.get(maker!!, "mMainPreviewSurfaceSize") as Size?
            if (size == null) return
            val sizes =
                previewSupport.computeIfAbsent(id) { ignored: String? ->
                    ConcurrentHashMap<Size?, Boolean?>()
                }
            var supported = sizes!!.get(size)
            val characteristics = cameraCharacteristics(id) ?: return
            // Cache immutable stream capabilities; live Android/Samsung thermal limits are checked
            // below.
            if (supported == null) {
                val ranges =
                    characteristics.get<Array<Range<Int>?>?>(
                        CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES,
                    )
                val outputs =
                    characteristics.get<StreamConfigurationMap?>(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP,
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
            val builders = Reflect.get(maker, "mPreviewRequestBuilderMap") as? Map<*, *> ?: return
            val fpsKey = CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE
            var highRate = false
            var cameraHeat = 0
            var armed = false
            fun armForWrite() {
                if (!armed) {
                    rt.guard.arm()
                    armed = true
                }
            }

            for ((key, value) in builders) {
                val builder = value as? Builder ?: continue
                val heat = builder.get(CAMERA_HEAT) ?: 0
                cameraHeat = max(cameraHeat, heat)
                synchronized(builder) {
                    val saved = previewOverrides[builder]

                    if (thermalStatus >= 3 || heat > 0) {
                        if (saved == null) continue
                        if (saved.ownsGlobal && builder.get(fpsKey) == PREVIEW_60) {
                            armForWrite()
                            builder.set(fpsKey, saved.global)
                        }
                        for ((physicalId, original) in saved.physical) {
                            if (builder.getPhysicalCameraKey(fpsKey, physicalId) == PREVIEW_60) {
                                armForWrite()
                                builder.setPhysicalCameraKey(fpsKey, original, physicalId)
                            }
                        }
                        previewOverrides.remove(builder)
                        continue
                    }

                    highRate = true
                    val global = builder.get(fpsKey)
                    if (global != PREVIEW_60) {
                        armForWrite()
                        val original = previewOverrides.getOrPut(builder) { SavedPreviewFps() }
                        builder.set(fpsKey, PREVIEW_60)
                        original.ownsGlobal = true
                        original.global = global
                    }
                    // Samsung can replace just the physical value while leaving the logical
                    // value alone. Check both before writing and remember only values we change.
                    val physicalIds = if (physicalFps) (key as? Pair<*, *>)?.second as? Set<*> else null
                    for (idValue in physicalIds.orEmpty()) {
                        val physicalId = idValue as? String ?: continue
                        val current = builder.getPhysicalCameraKey(fpsKey, physicalId)
                        if (current == PREVIEW_60) continue
                        armForWrite()
                        val original = previewOverrides.getOrPut(builder) { SavedPreviewFps() }
                        builder.setPhysicalCameraKey(fpsKey, PREVIEW_60, physicalId)
                        original.physical[physicalId] = current
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
                        stream),
                )
            }
        } catch (unsupported: IllegalArgumentException) {
            // A stale or unsupported stream keeps the native setup, without poisoning the host.
            ModernHook.log(
                "Photo preview: retaining native stream: " + unsupported.javaClass.getSimpleName(),
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
        zoomLastResult = null
    }

    @Synchronized
    private fun stopZoomMeasurement() {
        zoomUntil = 0
        zoomLastResult = null
    }

    @Synchronized
    private fun measureZoom(result: TotalCaptureResult, timestamp: Long, now: Long) {
        if (zoomUntil == 0L) return
        if (now >= zoomUntil) {
            val last = zoomLastResult
            val request = last?.request
            if (zoomIntervals > 0)
                ModernHook.log(
                    String.format(
                        Locale.ROOT,
                        "Photo zoom preview: %.1f fps; intervals=%d; over25ms=%d; requested=%s; result=%s;" +
                            " exposure=%s; cameraHeat=%s",
                        zoomIntervals * 1000000000.0 / zoomTotalNanos,
                        zoomIntervals,
                        zoomSlowIntervals,
                        request?.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE),
                        last?.get(CaptureResult.CONTROL_AE_TARGET_FPS_RANGE),
                        last?.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                        request?.get(CAMERA_HEAT),
                    ),
                )
            zoomUntil = 0
            zoomLastResult = null
            return
        }
        val interval: Long = timestamp - zoomLastTimestamp
        if (zoomLastTimestamp > 0 && interval > 0 && interval < 100000000L) {
            zoomIntervals++
            zoomTotalNanos += interval
            if (interval > 25000000L) zoomSlowIntervals++
        }
        zoomLastTimestamp = timestamp
        // The report uses only the last result. Read its metadata once at the end.
        zoomLastResult = result
    }

    private fun measure(result: TotalCaptureResult) {
        samples++
        previewFrameNumber = result.frameNumber
        val now = SystemClock.uptimeMillis()
        val readStatistics = now >= nextStatisticsRead
        if (readStatistics) {
            nextStatisticsRead = now + 200
            sampledHeat = result.request.get(CAMERA_HEAT) ?: 0
        }
        val zooming = zoomUntil != 0L
        if (!zooming && !readStatistics) return
        val timestamp = result.get<Long?>(CaptureResult.SENSOR_TIMESTAMP)
        if (timestamp == null) return
        if (zooming) measureZoom(result, timestamp, now)
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
                ),
            )
            firstTimestamp = timestamp
            firstFrame = result.getFrameNumber()
            samples = 1
        }
    }

    companion object {
        private val PREVIEW_60: Range<Int> = Range<Int>(60, 60)
        private val CAMERA_HEAT: Key<Int?> =
            Key<Int?>("samsung.android.control.overHeatHint", CameraMetadataTypes.integer)
    }
}
