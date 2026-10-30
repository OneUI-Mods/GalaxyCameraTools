package dev.ryuya.galaxycamera

import android.database.ContentObserver
import android.os.Bundle
import io.github.libxposed.api.XposedInterface.Chain
import java.lang.Boolean as JavaBoolean
import java.lang.ref.WeakReference
import kotlin.concurrent.Volatile
import kotlin.math.log

/* Keeps Samsung's tile and safety checks, routing only the camera-owned torch request. */
class QuickPanelLight17(runtime: HostRuntime) {
    private val runtime: HostRuntime
    @Volatile private var cameraAvailable: Boolean = false
    @Volatile private var cameraTorchEnabled: Boolean = false
    private var controller: WeakReference<Any?> = WeakReference<Any?>(null)
    private val tileClick: ThreadLocal<Boolean?> = ThreadLocal<Boolean?>()
    private val requestedTileState: ThreadLocal<Boolean?> = ThreadLocal<Boolean?>()
    private val refreshLease: Runnable = Runnable { this.readCameraState() }

    init {
        this.runtime = runtime
    }

    @Throws(Exception::class)
    fun install() {
        val loader = runtime.loader
        val flashlight =
            Class.forName(
                "com.android.systemui.statusbar.policy.FlashlightControllerImpl",
                false,
                loader,
            )
        val tile = Class.forName("com.android.systemui.qs.tiles.FlashlightTile", false, loader)
        val expandable = Class.forName("com.android.systemui.animation.Expandable", false, loader)
        ModernHook.hookAllConstructors(
            flashlight,
            object : ModernHook.Callback() {
                override fun afterHookedMethod(call: ModernHook.Param) {
                    if (!call.hasThrowable()) controller = WeakReference<Any?>(call.thisObject)
                }
            },
        )
        ModernHook.findAndHookMethod(
            flashlight,
            "isAvailable",
            object : ModernHook.Callback() {
                override fun afterHookedMethod(call: ModernHook.Param) {
                    if (call.hasThrowable() || !relayEnabled()) return
                    try {
                        val samsung = Reflect.get(call.thisObject!!, "mSecFlashlightController")
                        if (
                            samsung != null &&
                                (Reflect.call(samsung, "isThermalRestricted") as Boolean ||
                                    Reflect.call(samsung, "isFlashlightBlinking") as Boolean)
                        )
                            return
                        call.setResult(true)
                    } catch (error: Throwable) {
                        runtime.fail(error)
                    }
                }
            },
        )
        ModernHook.findAndHookMethod(
            flashlight,
            "isEnabled",
            object : ModernHook.Callback() {
                override fun afterHookedMethod(call: ModernHook.Param) {
                    if (!call.hasThrowable() && relayEnabled()) call.setResult(cameraTorchEnabled)
                }
            },
        )
        ModernHook.aroundMethod(
            Reflect.method(tile, "handleClick", expandable)!!,
            ModernHook.Around { chain: Chain? ->
                tileClick.set(true)
                if (relayEnabled()) {
                    requestedTileState.set(!cameraTorchEnabled)
                    val state = Reflect.get(chain!!.getThisObject(), "mState")
                    Reflect.field(state!!.javaClass, "value")!!.setBoolean(
                        state,
                        cameraTorchEnabled,
                    )
                }
                try {
                    return@Around chain!!.proceed()
                } finally {
                    tileClick.remove()
                    requestedTileState.remove()
                }
            },
        )
        ModernHook.findAndHookMethod(
            flashlight,
            "setFlashlight",
            Boolean::class.javaPrimitiveType,
            object : ModernHook.Callback() {
                override fun beforeHookedMethod(call: ModernHook.Param) {
                    val enable: Boolean =
                        (if (requestedTileState.get() != null) requestedTileState.get()
                        else call.args[0] as Boolean)!!
                    if (!relayEnabled() || (enable && JavaBoolean.TRUE != tileClick.get())) return
                    try {
                        val samsung = Reflect.get(call.thisObject!!, "mSecFlashlightController")
                        if (
                            enable &&
                                samsung != null &&
                                Reflect.call(samsung, "isThermalRestricted") as Boolean
                        )
                            return
                        runtime.guard.arm()
                        call.setResult(null)
                        runtime.worker.post(Runnable { sendRequest(enable) })
                    } catch (error: Throwable) {
                        runtime.fail(error)
                    }
                }
            },
        )
        val tileState =
            Class.forName("com.android.systemui.plugins.qs.QSTile\$State", false, loader)
        ModernHook.findAndHookMethod(
            tile,
            "handleUpdateState",
            tileState,
            Any::class.java,
            object : ModernHook.Callback() {
                override fun beforeHookedMethod(call: ModernHook.Param) {
                    // Reopening the panel delivers Samsung's cached CameraManager state.
                    // Present the camera owner's acknowledgement instead of that stale OFF.
                    if (relayEnabled()) call.args[1] = cameraTorchEnabled
                }
            },
        )
        // Brightness/blink controls own separate CameraManager requests and must not
        // contend with Camera. The normal tile click and native controls return on exit.
        ModernHook.findAndHookMethod(
            tile,
            "isDetailAvailable",
            object : ModernHook.Callback() {
                override fun afterHookedMethod(call: ModernHook.Param) {
                    if (!call.hasThrowable() && relayEnabled()) call.setResult(false)
                }
            },
        )
        runtime.context
            .getContentResolver()
            .registerContentObserver(
                CameraLightRelay.STATE_URI!!,
                false,
                object : ContentObserver(runtime.worker) {
                    override fun onChange(own: Boolean) {
                        readCameraState()
                    }
                },
            )
        runtime.onChanged(Runnable { runtime.worker.post(Runnable { this.readCameraState() }) })
        runtime.worker.post(Runnable { this.readCameraState() })
        runtime.ready()
    }

    private fun relayEnabled(): Boolean {
        return cameraAvailable && runtime.enabled(ConfigProvider.PANEL_LIGHT)
    }

    private fun sendRequest(enable: Boolean) {
        try {
            val input = Bundle()
            input.putBoolean("enabled", enable)
            val result =
                runtime.context
                    .getContentResolver()
                    .call(ConfigProvider.URI!!, "light_request", null, input)
            ModernHook.log(
                ("Camera light request=" +
                    enable +
                    "; accepted=" +
                    (result != null && result.getBoolean("accepted")))
            )
            if (result == null || !result.getBoolean("accepted")) readCameraState()
        } catch (error: RuntimeException) {
            cameraAvailable = false
            notifyTile()
        }
    }

    private fun readCameraState() {
        runtime.worker.removeCallbacks(refreshLease)
        val previousAvailable: Boolean = cameraAvailable
        val previousEnabled: Boolean = cameraTorchEnabled
        try {
            val state =
                runtime.context
                    .getContentResolver()
                    .call(ConfigProvider.URI!!, "light_read", null, null)
            cameraAvailable = state != null && state.getBoolean("available")
            cameraTorchEnabled = cameraAvailable && state!!.getBoolean("enabled")
        } catch (error: RuntimeException) {
            cameraAvailable = false
            cameraTorchEnabled = false
        }
        if (previousAvailable != cameraAvailable || previousEnabled != cameraTorchEnabled) {
            ModernHook.log(
                "Camera light state available=" +
                    cameraAvailable +
                    "; enabled=" +
                    cameraTorchEnabled
            )
            notifyTile()
        }
        if (cameraAvailable) runtime.worker.postDelayed(refreshLease, 1000)
    }

    private fun notifyTile() {
        runtime.main.post(
            Runnable {
                val current = controller.get()
                if (current == null) return@Runnable
                try {
                    val available: Boolean = Reflect.call(current, "isAvailable") as Boolean
                    val enabled: Boolean = Reflect.call(current, "isEnabled") as Boolean
                    val dispatch =
                        Reflect.method(
                            current.javaClass,
                            "dispatchListeners",
                            Int::class.javaPrimitiveType,
                            Boolean::class.javaPrimitiveType,
                        )
                    dispatch!!.invoke(current, 2, available)
                    dispatch!!.invoke(current, 1, enabled)
                } catch (error: Throwable) {
                    runtime.fail(error)
                }
            }
        )
    }
}
