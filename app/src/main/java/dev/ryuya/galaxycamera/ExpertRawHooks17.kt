package dev.ryuya.galaxycamera

import android.app.Activity
import android.graphics.Rect
import android.os.Looper
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Supplier
import kotlin.jvm.JvmField
import kotlin.jvm.JvmStatic
import kotlin.math.log

/* Reuses Expert RAW's own snapshot, blur and first-preview-frame handoff. */
class ExpertRawHooks17(runtime: HostRuntime) {
    private val rt: HostRuntime
    private val original: ThreadLocal<Boolean?>? =
        ThreadLocal.withInitial<Boolean?>(Supplier { false })
    private var pending: ExpertRawHooks17.Pending? = null
    private var motion: RawLensMotion17? = null
    private var queuedLens: Int? = null
    private var animationType: Class<*>? = null
    private var callbackType: Class<*>? = null
    private var engineState: Class<*>? = null
    private var previewChange: Any? = null
    private var previewing: Any? = null
    private var switchCamera: Method? = null
    private var request: Method? = null

    init {
        rt = runtime
    }

    @Throws(Exception::class)
    fun install() {
        val l = rt.loader
        val engine = Class.forName(ExpertRawHooks17.BASE + "engine.CommonEngine", false, l)
        val animation =
            Class.forName(
                ExpertRawHooks17.BASE + "layer.previewanimation.PreviewAnimationLayerView",
                false,
                l,
            )
        animationType =
            Class.forName(
                ExpertRawHooks17.BASE +
                    "interfaces.PreviewAnimationLayerManager\$PreviewAnimationType",
                false,
                l,
            )
        callbackType =
            Class.forName(
                ExpertRawHooks17.BASE +
                    "interfaces.PreviewAnimationLayerManager\$PreviewSnapshotEventListener",
                false,
                l,
            )
        engineState = Class.forName(ExpertRawHooks17.BASE + "interfaces.Engine\$State", false, l)
        previewChange = Reflect.value(animationType, "PREVIEW_CHANGE")
        previewing = Reflect.value(engineState, "PREVIEWING")
        request =
            Reflect.method(
                animation,
                "requestPreviewAnimation",
                animationType,
                callbackType,
                Rect::class.java,
            )
        switchCamera = Reflect.method(engine, "switchCamera", Int::class.javaPrimitiveType)
        val presenter =
            Class.forName(
                ExpertRawHooks17.BASE + "shootingmode.abstraction.AbstractShootingModePresenter",
                false,
                l,
            )
        ModernHook.findAndHookMethod(
            presenter,
            "switchCamera",
            Int::class.javaPrimitiveType,
            object : ModernHook.Callback() {
                override fun beforeHookedMethod(p: ModernHook.Param) {
                    if (
                        !rt.enabled(ConfigProvider.ANIMATION) ||
                            Looper.myLooper() != Looper.getMainLooper()
                    )
                        return
                    try {
                        ModernHook.log("Expert RAW presenter lens request=" + p.args[0])
                        val source = Reflect.get(p.thisObject!!, "mEngine")
                        if (prepare(source!!, p.args[0] as Int)) {
                            Reflect.call(Reflect.call(source!!, "getAeAfManager")!!, "resetAeAfAwb")
                            p.setResult(null)
                        }
                    } catch (e: Throwable) {
                        rt.fail(e)
                    }
                }
            },
        )
        ModernHook.deoptimize(
            Reflect.method(
                Class.forName(ExpertRawHooks17.BASE + "shootingmode.pro.ProPresenter", false, l),
                "handleLensTypeChanged",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )!!
        )
        val pro = Class.forName(ExpertRawHooks17.BASE + "shootingmode.pro.ProPresenter", false, l)
        val settingKey =
            Class.forName(ExpertRawHooks17.BASE + "interfaces.CameraSettings\$Key", false, l)
        for (caller in
            arrayOf<Method?>(
                Reflect.method(
                    pro,
                    "lambda\$initializeSettingChangeConsumerMap$53",
                    settingKey,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                ),
                Reflect.method(
                    pro,
                    "onCameraSettingChanged",
                    settingKey,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                ),
                Reflect.method(
                    Class.forName(ExpertRawHooks17.BASE + "shootingmode.pro.j", false, l),
                    "onCameraSettingChanged",
                    settingKey,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                ),
                Reflect.method(presenter, "switchCamera", Int::class.javaPrimitiveType),
            )) {
            ModernHook.deoptimize(caller!!)
        }
        ModernHook.hookMethod(
            switchCamera!!,
            object : ModernHook.Callback() {
                override fun beforeHookedMethod(p: ModernHook.Param) {
                    if (
                        original!!.get()!! ||
                            Looper.myLooper() != Looper.getMainLooper() ||
                            !rt.enabled(ConfigProvider.ANIMATION)
                    )
                        return
                    try {
                        rt.report("EXPERT RAW: lens request " + p.args[0])
                        if (prepare(p.thisObject!!, p.args[0] as Int)) p.setResult(null)
                    } catch (e: Throwable) {
                        rt.fail(e)
                    }
                }
            },
        )
        ModernHook.findAndHookMethod(
            animation,
            "onPreviewSnapShotError",
            object : ModernHook.Callback() {
                override fun afterHookedMethod(p: ModernHook.Param) {
                    if (pending != null && pending!!.layer === p.thisObject) pending!!.finish(false)
                }
            },
        )
        ModernHook.findAndHookMethod(
            animation,
            "startPreviewChangeAnimation",
            object : ModernHook.Callback() {
                override fun beforeHookedMethod(p: ModernHook.Param) {
                    if (motion != null && motion!!.owns(p.thisObject)) p.setResult(null)
                }
            },
        )
        ModernHook.findAndHookMethod(
            animation,
            "hidePreviewAnimation",
            Rect::class.java,
            object : ModernHook.Callback() {
                override fun beforeHookedMethod(p: ModernHook.Param) {
                    if (motion != null && motion!!.owns(p.thisObject)) {
                        motion!!.onPreviewReady()
                        p.setResult(null)
                    }
                }
            },
        )
        val raw = Class.forName(ExpertRawHooks17.BASE + "GalaxyRaw", false, l)
        ModernHook.findAndHookMethod(
            raw,
            "onPause",
            object : ModernHook.Callback() {
                override fun beforeHookedMethod(p: ModernHook.Param) {
                    queuedLens = null
                    if (pending != null) pending!!.cancel()
                    if (motion != null) motion!!.finish()
                }
            },
        )
        rt.onChanged(
            Runnable {
                if (!rt.enabled(ConfigProvider.ANIMATION)) {
                    queuedLens = null
                    if (pending != null) pending!!.finish(false)
                    if (motion != null) motion!!.finish()
                }
            }
        )
        rt.ready()
    }

    @Throws(Exception::class)
    private fun prepare(engine: Any, cameraId: Int): Boolean {
        if (motion != null) {
            queuedLens = cameraId
            return true
        }
        // Existing native facing / ratio / resolution animations keep their own callback.
        if (pending != null) {
            if (pending!!.engine === engine) {
                pending!!.cameraId = cameraId
                return true
            }
            pending!!.cancel()
        }
        val context = Reflect.get(engine, "mCameraContext")
        if (
            !(Reflect.call(context!!, "isRunning") as Boolean) ||
                Reflect.call(context!!, "isCapturing") as Boolean
        )
            return false
        if (
            !(Reflect.method(engine.javaClass, "isCurrentState", engineState)!!.invoke(
                engine,
                previewing,
            ) as Boolean)
        )
            return false
        val activity: Activity? = Reflect.call(context!!, "getActivity") as Activity?
        if (activity!!.isFinishing() || activity!!.isDestroyed()) return false
        val layer =
            Reflect.call(
                Reflect.call(context!!, "getLayerManager")!!,
                "getPreviewAnimationLayerManager",
            )
        val currentAnimation = Reflect.call(layer!!, "getCurrentAnimationType").toString()
        val adoptSnapshot: Boolean =
            "PREVIEW_CHANGE" == currentAnimation &&
                Reflect.get(layer!!, "mPreviewEventListener") == null
        if ("NONE" != currentAnimation && !adoptSnapshot) return false
        val settings = Reflect.call(context!!, "getCameraSettings")
        if (Reflect.call(settings!!, "getCameraId") as Int == cameraId) {
            rt.report("EXPERT RAW: settings already reference requested lens")
            ModernHook.log("Expert RAW requested lens already selected: " + cameraId)
            return false
        }
        val rect: Rect? =
            Reflect.call(Reflect.call(context!!, "getPreviewManager")!!, "getPreviewLayoutRect")
                as Rect?
        if (rect == null || rect.isEmpty()) return false
        rt.guard.arm()
        val oldCameraId: Int = Reflect.call(settings!!, "getCameraId") as Int
        val next = Pending(engine, cameraId, context, layer, oldCameraId)
        pending = next
        val callback =
            Proxy.newProxyInstance(
                rt.loader,
                arrayOf<Class<*>?>(callbackType),
                InvocationHandler { proxy: Any?, method: Method?, args: Array<Any?>? ->
                    if (method!!.getDeclaringClass() == Any::class.java)
                        return@InvocationHandler ExpertRawHooks17.objectMethod(
                            proxy,
                            method!!,
                            args!!,
                        )
                    if (method!!.getName() == "onPreviewSnapshotShow") next.finish(true)
                    null
                },
            )
        try {
            rt.main.postDelayed(next.timeout, 600)
            if (adoptSnapshot) {
                Reflect.field(layer!!.javaClass, "mPreviewEventListener")!!.set(layer, callback)
                if (Reflect.get(layer!!, "mSnapShotTaken") as Boolean)
                    rt.main.post(Runnable { next.finish(true) })
                ModernHook.log("Expert RAW: adopted native lens snapshot")
            } else {
                request!!.invoke(layer, previewChange, callback, Rect(rect))
            }
            rt.report("EXPERT RAW: waiting for preview snapshot")
            return true
        } catch (error: Throwable) {
            next.cancel()
            throw error
        }
    }

    private inner class Pending(e: Any, id: Int, c: Any, view: Any, oldId: Int) {
        @JvmField val engine: Any
        @JvmField val context: Any
        @JvmField val layer: Any
        @JvmField val oldCameraId: Int
        @JvmField var cameraId: Int
        @JvmField val done: AtomicBoolean = AtomicBoolean()
        @JvmField val timeout: Runnable = Runnable { finish(false) }

        init {
            oldCameraId = oldId
            engine = e
            cameraId = id
            context = c
            layer = view
        }

        fun cancel() {
            if (!done.compareAndSet(false, true)) return
            rt.main.removeCallbacks(timeout)
            if (pending == this) pending = null
            try {
                Reflect.call(layer, "cancelAllAnimator")
                Reflect.call(layer, "cancelPreviewAnimation")
            } catch (ignored: ReflectiveOperationException) {}
        }

        fun finish(snapshot: Boolean) {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                rt.main.post(Runnable { finish(snapshot) })
                return
            }
            if (!done.compareAndSet(false, true)) return
            rt.main.removeCallbacks(timeout)
            if (pending == this) pending = null
            try {
                if (!snapshot) {
                    Reflect.call(layer, "cancelAllAnimator")
                    Reflect.call(layer, "cancelPreviewAnimation")
                }
                val activity: Activity? = Reflect.call(context, "getActivity") as Activity?
                if (
                    activity!!.isFinishing() ||
                        activity!!.isDestroyed() ||
                        !(Reflect.call(context, "isRunning") as Boolean)
                )
                    return
                if (snapshot && rt.enabled(ConfigProvider.ANIMATION)) {
                    try {
                        motion =
                            RawLensMotion17(
                                rt,
                                context,
                                layer,
                                oldCameraId,
                                cameraId,
                                Runnable {
                                    motion = null
                                    val nextLens: Int? = queuedLens
                                    queuedLens = null
                                    if (nextLens != null && rt.enabled(ConfigProvider.ANIMATION)) {
                                        rt.main.post(
                                            Runnable {
                                                try {
                                                    if (!prepare(engine, nextLens))
                                                        switchCamera!!.invoke(engine, nextLens)
                                                } catch (error: Throwable) {
                                                    rt.fail(error)
                                                }
                                            }
                                        )
                                    }
                                },
                            )
                        motion!!.start()
                    } catch (unsupported: Exception) {
                        motion = null
                        ModernHook.log(
                            "Expert RAW lens geometry unavailable; retaining native blur: " +
                                unsupported
                        )
                    }
                }
                original!!.set(true)
                try {
                    switchCamera!!.invoke(engine, cameraId)
                } finally {
                    original!!.remove()
                }
                rt.report(
                    if (snapshot) "EXPERT RAW: native preview transition"
                    else "EXPERT RAW: snapshot unavailable; native lens switch"
                )
            } catch (e: Throwable) {
                if (motion != null) motion!!.finish()
                rt.fail(e)
            }
        }
    }

    companion object {
        private const val BASE: String = "com.samsung.android.app.galaxyraw."

        @JvmStatic
        private fun objectMethod(proxy: Any?, method: Method, args: Array<Any?>): Any? {
            return when (method.getName()) {
                "equals" -> proxy === args[0]
                "hashCode" -> System.identityHashCode(proxy)
                else -> "Galaxy Camera Tools snapshot callback"
            }
        }
    }
}
