package dev.ryuya.galaxycamera

import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.util.Size
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.log

/* Changes the producer buffer before the stock preview surface and session are configured. */
class PhotoPreviewStream17(runtime: HostRuntime, camera: CameraHooks17) {
    private val runtime: HostRuntime
    private val camera: CameraHooks17
    private val selectedSizes: MutableMap<Size?, Size?> = ConcurrentHashMap<Size?, Size?>()

    init {
        this.runtime = runtime
        this.camera = camera
    }

    @Throws(Exception::class)
    fun install() {
        val surfaceManager = Class.forName("vb.g", false, runtime.loader)
        val resolution = Class.forName("l9.a2", false, runtime.loader)
        ModernHook.deoptimize(
            Reflect.method(
                surfaceManager,
                "a",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )!!
        )
        ModernHook.deoptimize(
            Reflect.method(
                Class.forName("vb.c", false, runtime.loader),
                "k",
                Rect::class.java,
                resolution,
            )!!
        )
        // Photo's deferred-surface profile otherwise replaces the chosen dimensions with 1920x1440.
        ModernHook.findAndHookMethod(
            Class.forName("gd.i", false, runtime.loader),
            "p",
            object : ModernHook.Callback() {
                override fun afterHookedMethod(call: ModernHook.Param) {
                    if (
                        call.hasThrowable() ||
                            call.getResult() == null ||
                            !runtime.enabled(ConfigProvider.LIGHT_PREVIEW) ||
                            camera.mode() != 0 ||
                            !camera.rearCamera()
                    )
                        return
                    try {
                        val original = call.getResult()
                        val size =
                            Size(
                                Reflect.call(original!!, "m") as Int,
                                Reflect.call(original!!, "d") as Int,
                            )
                        var selected = selectedSizes.get(size)
                        if (selected == null) {
                            selected = choose(size)
                            selectedSizes.put(size, selected)
                        }
                        if (selected != size) {
                            runtime.guard.arm()
                            call.setResult(
                                Reflect.value(
                                    resolution,
                                    "RESOLUTION_" +
                                        selected!!.getWidth() +
                                        "X" +
                                        selected!!.getHeight(),
                                )
                            )
                        }
                    } catch (unsupported: Exception) {
                        ModernHook.log("Photo preview profile: keeping native size")
                    }
                }
            },
        )
        ModernHook.findAndHookMethod(
            surfaceManager,
            "a",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            object : ModernHook.Callback() {
                override fun beforeHookedMethod(call: ModernHook.Param) {
                    if (!runtime.enabled(ConfigProvider.LIGHT_PREVIEW) || camera.mode() != 0) return
                    try {
                        val settings = Reflect.get(Reflect.get(call.thisObject!!, "a")!!, "j")
                        if (Reflect.call(settings!!, "f") as Int != 0) return
                        val requested = Size(call.args[0] as Int, call.args[1] as Int)
                        var selected = selectedSizes.get(requested)
                        if (selected == null) {
                            selected = choose(requested)
                            selectedSizes.put(requested, selected)
                        }
                        if (selected == requested) return
                        runtime.guard.arm()
                        call.args[0] = selected!!.getWidth()
                        call.args[1] = selected!!.getHeight()
                    } catch (unsupported: Exception) {
                        ModernHook.log("Photo preview stream: keeping native size")
                    }
                }

                @Throws(Exception::class)
                override fun afterHookedMethod(call: ModernHook.Param) {
                    if (
                        !call.hasThrowable() &&
                            runtime.enabled(ConfigProvider.LIGHT_PREVIEW) &&
                            camera.mode() == 0
                    )
                        ModernHook.log(
                            "Photo preview configured: " + Reflect.get(call.thisObject!!, "k")
                        )
                }
            },
        )
    }

    @Throws(Exception::class)
    private fun choose(requested: Size): Size? {
        val characteristics =
            runtime.context
                .getSystemService<CameraManager?>(CameraManager::class.java)
                .getCameraCharacteristics("20")
        val outputs =
            characteristics.get<StreamConfigurationMap?>(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            )
        if (outputs == null) return requested
        val sizes = outputs.getOutputSizes<SurfaceTexture?>(SurfaceTexture::class.java)
        if (sizes == null) return requested
        var best: Size? = requested
        var bestArea: Long = 0
        for (size in sizes) {
            val area: Long = size.getWidth().toLong() * size.getHeight()
            if (
                size.getWidth() > 1440 ||
                    size.getHeight() > 1440 ||
                    area > 1440L * 1080 ||
                    area < 960L * 720 ||
                    area >= requested.getWidth().toLong() * requested.getHeight() ||
                    (size.getWidth().toLong() * requested.getHeight() !=
                        requested.getWidth().toLong() * size.getHeight())
            )
                continue
            val duration =
                outputs.getOutputMinFrameDuration<SurfaceTexture?>(SurfaceTexture::class.java, size)
            if (duration <= 0 || duration > 16666667L) continue
            if (area > bestArea) {
                best = size
                bestArea = area
            }
        }
        return best
    }
}
