package dev.ryuya.galaxycamera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.os.Parcel
import io.github.libxposed.api.XposedInterface.Chain
import java.util.ArrayList
import java.util.Arrays
import java.util.Collections
import java.util.LinkedHashSet
import java.util.Set
import java.util.WeakHashMap
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmStatic
import kotlin.math.log

/* Presents supported physical lenses through Android's native logical-camera output routing. */
class Camera2Unlock17(runtime: HostRuntime) {
    private val runtime: HostRuntime
    private val openingPhysicalLens: ThreadLocal<String?> = ThreadLocal<String?>()
    private val nativeRequestDepth: ThreadLocal<Int?> = ThreadLocal<Int?>()
    private val openedDevices: MutableMap<Any?, String?>? =
        Collections.synchronizedMap<Any?, String?>(WeakHashMap<Any?, String?>())
    @Volatile
    private var availablePhysicalLenses: MutableSet<String?>? = java.util.Set.of<String?>()

    init {
        this.runtime = runtime
    }

    @Throws(Exception::class)
    fun install() {
        exposePhysicalLensChoices()
        routeLensOpenRequests()
        routeSessionOutputs()
        ModernHook.log("Camera2 physical lens selection installed")
    }

    private fun exposePhysicalLensChoices() {
        ModernHook.findAndHookMethod(
            CameraManager::class.java,
            "getCameraIdList",
            object : ModernHook.Callback() {
                override fun afterHookedMethod(call: ModernHook.Param) {
                    if (call.hasThrowable() || !runtime.enabled(ConfigProvider.CAMERA2)) return
                    try {
                        val manager: CameraManager? = call.thisObject as CameraManager?
                        val logical =
                            manager!!.getCameraCharacteristics(Camera2Unlock17.LOGICAL_REAR_CAMERA)
                        val supported = LinkedHashSet<String?>(logical.getPhysicalCameraIds())
                        supported.retainAll(Camera2Unlock17.REVIEWED_PHYSICAL_LENSES!!)
                        for (physical in ArrayList<String?>(supported)) {
                            val facing =
                                manager!!
                                    .getCameraCharacteristics(physical!!)
                                    .get<Int?>(CameraCharacteristics.LENS_FACING)
                            if (facing == null || facing != CameraCharacteristics.LENS_FACING_BACK)
                                supported.remove(physical)
                        }
                        availablePhysicalLenses = Set.copyOf<String?>(supported)
                        val choices =
                            LinkedHashSet<String?>(
                                Arrays.asList<String?>(*(call.getResult() as Array<String?>))
                            )
                        supported.sortedWith(compareBy(nullsFirst()) { it }).forEach { e ->
                            choices.add(e)
                        }
                        call.setResult(choices.toTypedArray())
                    } catch (error: Throwable) {
                        runtime.fail(error)
                    }
                }
            },
        )
    }

    @Throws(Exception::class)
    private fun routeLensOpenRequests() {
        val device =
            Class.forName("android.hardware.camera2.impl.CameraDeviceImpl", false, runtime.loader)
        ModernHook.hookAllConstructors(
            device,
            object : ModernHook.Callback() {
                override fun afterHookedMethod(call: ModernHook.Param) {
                    val physical = openingPhysicalLens.get()
                    if (!call.hasThrowable() && physical != null)
                        openedDevices!!.put(call.thisObject, physical)
                }
            },
        )
        // Keep the service-facing camera ID intact. Only the app's public device ID
        // describes the selected physical lens; the native session still belongs to 0.
        ModernHook.findAndHookMethod(
            device,
            "getId",
            object : ModernHook.Callback() {
                override fun afterHookedMethod(call: ModernHook.Param) {
                    val physical = openedDevices!!.get(call.thisObject)
                    if (
                        !call.hasThrowable() && physical != null && nativeRequestDepth.get() == null
                    )
                        call.setResult(physical)
                }
            },
        )
        // Android serializes the logical ID into every request. Keep that ID at 0
        // while native builders are created; public getId() still names the selected lens.
        for (method in device.getDeclaredMethods()) {
            if (
                !java.util.Set.of<String?>(
                        "createCaptureRequest",
                        "createReprocessCaptureRequest",
                        "startStreaming",
                    )
                    .contains(method.getName())
            )
                continue
            ModernHook.aroundMethod(
                method,
                ModernHook.Around { chain: Chain? ->
                    if (!openedDevices!!.containsKey(chain!!.getThisObject()))
                        return@Around chain!!.proceed()
                    val previousDepth = nativeRequestDepth.get()
                    nativeRequestDepth.set(if (previousDepth == null) 1 else previousDepth + 1)
                    try {
                        return@Around chain!!.proceed()
                    } finally {
                        if (previousDepth == null) nativeRequestDepth.remove()
                        else nativeRequestDepth.set(previousDepth)
                    }
                },
            )
        }
        for (method in CameraManager::class.java.getDeclaredMethods()) {
            if (method.getName() != "openCameraDeviceUserAsync") continue
            ModernHook.hookMethod(
                method,
                object : ModernHook.Callback() {
                    override fun beforeHookedMethod(call: ModernHook.Param) {
                        if (
                            !runtime.enabled(ConfigProvider.CAMERA2) ||
                                !availablePhysicalLenses!!.contains(call.args[0])
                        )
                            return
                        runtime.guard.arm()
                        val selectedPhysicalLens: String? = call.args[0] as String?
                        call.setObjectExtra("physical_lens", selectedPhysicalLens)
                        openingPhysicalLens.set(selectedPhysicalLens)
                        call.args[0] = Camera2Unlock17.LOGICAL_REAR_CAMERA
                    }

                    override fun afterHookedMethod(call: ModernHook.Param) {
                        if (call.getObjectExtra("physical_lens") != null)
                            openingPhysicalLens.remove()
                    }
                },
            )
        }
    }

    @Throws(Exception::class)
    private fun routeSessionOutputs() {
        val device =
            Class.forName("android.hardware.camera2.impl.CameraDeviceImpl", false, runtime.loader)
        for (method in device.getDeclaredMethods()) {
            if (method.getName() != "createCaptureSessionInternal") continue
            ModernHook.hookMethod(
                method,
                object : ModernHook.Callback() {
                    override fun beforeHookedMethod(call: ModernHook.Param) {
                        val physical = openedDevices!!.get(call.thisObject)
                        if (physical == null) return
                        try {
                            val originals: MutableList<*>? = call.args[1] as MutableList<*>?
                            val outputs = ArrayList<OutputConfiguration?>(originals!!.size)
                            for (original in originals!!) {
                                val output =
                                    Camera2Unlock17.copyOutput(original as OutputConfiguration)
                                output!!.setPhysicalCameraId(physical)
                                outputs.add(output)
                            }
                            call.args[1] = outputs
                            ModernHook.log("Camera2 session routed to physical lens " + physical)
                        } catch (error: Throwable) {
                            // Do not silently capture from a different lens if routing fails.
                            (call.thisObject as CameraDevice).close()
                            runtime.fail(error)
                        }
                    }
                },
            )
            ModernHook.deoptimize(method)
        }
    }

    companion object {
        private const val LOGICAL_REAR_CAMERA: String = "0"
        private val REVIEWED_PHYSICAL_LENSES: MutableSet<String?>? =
            java.util.Set.of<String?>("5", "6", "7")

        @JvmStatic
        private fun copyOutput(original: OutputConfiguration): OutputConfiguration? {
            val parcel = Parcel.obtain()
            try {
                original.writeToParcel(parcel, 0)
                parcel.setDataPosition(0)
                return OutputConfiguration.CREATOR.createFromParcel(parcel)
            } finally {
                parcel.recycle()
            }
        }
    }
}
