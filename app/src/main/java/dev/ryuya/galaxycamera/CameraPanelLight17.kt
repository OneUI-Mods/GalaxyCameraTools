package dev.ryuya.galaxycamera

import android.app.Activity
import android.database.ContentObserver
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureRequest.Key
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import kotlin.math.log

/* Receives flashlight requests inside Camera, which owns the active camera device. */
class CameraPanelLight17(runtime: HostRuntime) {
    private val runtime: HostRuntime
    private var activity: WeakReference<Activity?> = WeakReference<Activity?>(null)
    private var resumed: Boolean = false
    private var torchEnabled: Boolean = false
    private var lastCommandId: Long = 0
    private var changeTorch: Method? = null
    private val heartbeat: Runnable = Runnable { this.publishAndSchedule() }

    init {
        this.runtime = runtime
    }

    @Throws(Exception::class)
    fun install() {
        val camera = Class.forName("com.sec.android.app.camera.Camera", false, runtime.loader)
        changeTorch =
            Reflect.method(
                Class.forName("p8.p", false, runtime.loader),
                "I",
                Int::class.javaPrimitiveType,
            )
        ModernHook.findAndHookMethod(
            camera,
            "onResume",
            object : ModernHook.Callback() {
                override fun afterHookedMethod(call: ModernHook.Param) {
                    if (call.hasThrowable()) return
                    activity = WeakReference<Activity?>(call.thisObject as Activity?)
                    resumed = true
                    publishAndSchedule()
                }
            },
        )
        ModernHook.findAndHookMethod(
            camera,
            "onPause",
            object : ModernHook.Callback() {
                override fun beforeHookedMethod(call: ModernHook.Param) {
                    stopTorch()
                    resumed = false
                    publishAndSchedule()
                }
            },
        )
        // Native mode changes may reset the flash. Keep an explicit Quick Panel torch
        // request until OFF or leaving Camera; saved Photo/Video flash options are untouched.
        val maker =
            Class.forName("com.samsung.android.camera.core2.maker.MakerBase", false, runtime.loader)
        ModernHook.findAndHookMethod(
            maker,
            "setPublicSetting",
            Key::class.java,
            Any::class.java,
            object : ModernHook.Callback() {
                override fun beforeHookedMethod(call: ModernHook.Param) {
                    if (torchEnabled && CaptureRequest.FLASH_MODE == call.args[0])
                        call.args[1] = CaptureRequest.FLASH_MODE_TORCH
                }
            },
        )
        runtime.context
            .getContentResolver()
            .registerContentObserver(
                CameraLightRelay.COMMAND_URI!!,
                false,
                object : ContentObserver(runtime.worker) {
                    override fun onChange(own: Boolean) {
                        receiveRequest()
                    }
                },
            )
        runtime.onChanged(
            Runnable {
                if (!runtime.enabled(ConfigProvider.PANEL_LIGHT)) stopTorch()
                publishAndSchedule()
            }
        )
    }

    @Throws(ReflectiveOperationException::class)
    private fun focusController(): Any? {
        val camera = activity.get()
        if (camera == null || camera.isFinishing()) return null
        val engine = Reflect.get(camera, "n")
        return if (engine == null) null else Reflect.get(engine, "g")
    }

    @Throws(ReflectiveOperationException::class)
    private fun canControlTorch(): Boolean {
        if (!resumed || !runtime.enabled(ConfigProvider.PANEL_LIGHT)) return false
        val camera = activity.get()
        if (camera == null || camera.isFinishing()) return false
        val settings = Reflect.get(camera, "j")
        if (Reflect.call(settings!!, "f") as Int != 0) return false
        val key = Class.forName("l9.f0", false, runtime.loader)
        val mode: Int =
            Reflect.method(settings!!.javaClass, "x", key)!!.invoke(
                settings,
                Reflect.value(key, "MODE_CUSTOM_SETTING"),
            ) as Int
        val engine = Reflect.get(camera, "n")
        if (
            (mode != 0 && mode != 1) ||
                engine == null ||
                ("PREVIEWING" != Reflect.get(engine, "B").toString())
        )
            return false
        return (runtime.context
            .getSystemService<PowerManager?>(PowerManager::class.java)
            .getCurrentThermalStatus() < PowerManager.THERMAL_STATUS_SEVERE)
    }

    private fun receiveRequest() {
        try {
            val request =
                runtime.context
                    .getContentResolver()
                    .call(ConfigProvider.URI!!, "light_read", null, null)
            if (request == null) return
            runtime.main.post(
                Runnable {
                    val commandId = request.getLong("command_id")
                    if (commandId == lastCommandId) return@Runnable
                    lastCommandId = commandId
                    try {
                        if (
                            SystemClock.elapsedRealtime() - request.getLong("commanded_at") >
                                1500 || !canControlTorch()
                        )
                            return@Runnable
                        val controller = focusController()
                        if (controller == null) return@Runnable
                        runtime.guard.arm()
                        val requestedEnabled = request.getBoolean("requested_enabled")
                        torchEnabled = requestedEnabled
                        changeTorch!!.invoke(controller, if (requestedEnabled) 2 else 0)
                        runtime.report(
                            "QUICK PANEL: camera torch " + (if (requestedEnabled) "on" else "off")
                        )
                    } catch (error: Throwable) {
                        torchEnabled = false
                        runtime.fail(error)
                    }
                    publishAndSchedule()
                }
            )
        } catch (error: RuntimeException) {
            runtime.report("QUICK PANEL: request unavailable")
        }
    }

    private fun stopTorch() {
        if (!torchEnabled) return
        torchEnabled = false
        try {
            val controller = focusController()
            if (controller != null) changeTorch!!.invoke(controller, 0)
        } catch (error: ReflectiveOperationException) {
            ModernHook.log("Camera torch already disconnected")
        }
    }

    private fun publishAndSchedule() {
        runtime.main.removeCallbacks(heartbeat)
        var available: Boolean = false
        try {
            available = canControlTorch()
        } catch (ignored: ReflectiveOperationException) {}
        if (!available) stopTorch()
        val state = Bundle()
        state.putBoolean("available", available)
        state.putBoolean("enabled", available && torchEnabled)
        runtime.worker.post(
            Runnable {
                try {
                    runtime.context
                        .getContentResolver()
                        .call(ConfigProvider.URI!!, "light_state", null, state)
                } catch (ignored: RuntimeException) {}
            }
        )
        if (resumed && runtime.enabled(ConfigProvider.PANEL_LIGHT))
            runtime.main.postDelayed(heartbeat, 1000)
    }
}
