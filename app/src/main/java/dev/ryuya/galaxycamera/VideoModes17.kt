package dev.ryuya.galaxycamera

import java.lang.Boolean as JavaBoolean
import java.lang.reflect.Array as ReflectArray
import java.util.ArrayList
import java.util.Arrays
import java.util.Comparator
import java.util.function.ToIntFunction

/* Enables existing Samsung 120 fps pipelines in normal video, without inventing sensor modes. */
class VideoModes17(runtime: HostRuntime, hooks: CameraHooks17) {
    private val rt: HostRuntime
    private val camera: CameraHooks17
    private var resolution: Class<*>? = null
    private var commands: Class<*>? = null
    private var setting: Class<*>? = null
    private var features: Class<*>? = null
    private var uhd: Any? = null
    private var uhdCommand: Any? = null
    private var backSetting: Any? = null
    private val rawSelection: ThreadLocal<Boolean?> = ThreadLocal<Boolean?>()

    init {
        rt = runtime
        camera = hooks
    }

    @Throws(Exception::class)
    fun install() {
        val l = rt.loader
        resolution = Class.forName("l9.a2", false, l)
        commands = Class.forName("l9.j0", false, l)
        setting = Class.forName("l9.f0", false, l)
        features = Class.forName("te.f0", false, l)
        val util = Class.forName("te.c0", false, l)
        val commandMap = Class.forName("d8.r", false, l)
        uhd = Reflect.value(resolution, "RESOLUTION_3840X2160_120FPS")
        uhdCommand = Reflect.value(commands, "BACK_PRO_CAMCORDER_RESOLUTION_UHD_120")
        backSetting = Reflect.value(setting, "BACK_CAMCORDER_RESOLUTION")
        ModernHook.findAndHookMethod(
            Class.forName("com.sec.android.app.camera.Camera", false, l),
            "onResume",
            object : ModernHook.Callback() {
                override fun afterHookedMethod(p: ModernHook.Param) {
                    if (p.hasThrowable()) return
                    try {
                        camera.restoreNormalVideoSelection(resolution)
                    } catch (error: Throwable) {
                        rt.fail(error)
                    }
                }
            },
        )
        // Normalize a saved module-only choice before native session setup reads it.
        // onResume alone runs too late: engine initialization already consumed it.
        ModernHook.findAndHookMethod(
            Class.forName("vc.j", false, l),
            "d",
            setting,
            object : ModernHook.Callback() {
                override fun afterHookedMethod(p: ModernHook.Param) {
                    if (
                        p.hasThrowable() ||
                            p.args[0] !== backSetting ||
                            JavaBoolean.TRUE == rawSelection.get() ||
                            !rt.available() ||
                            rt.configured(ConfigProvider.VIDEO) ||
                            camera.busy()
                    )
                        return
                    try {
                        val selected =
                            Reflect.method(resolution!!, "j", Int::class.javaPrimitiveType)!!
                                .invoke(null, p.getResult())
                        val name = selected.toString()
                        if (
                            !java.util.Set.of<String?>(
                                    "RESOLUTION_1920X1080_120FPS",
                                    "RESOLUTION_3840X2160_120FPS",
                                )
                                .contains(name)
                        )
                            return
                        val fallback = Reflect.value(resolution, name.replace("_120FPS", ""))
                        val value: Int = Reflect.call(fallback!!, "e") as Int
                        p.setResult(value)
                        val settings = p.thisObject
                        rt.main.post(
                            Runnable {
                                if (rt.configured(ConfigProvider.VIDEO) || camera.busy())
                                    return@Runnable
                                rawSelection.set(true)
                                try {
                                    Reflect.method(
                                            settings!!.javaClass,
                                            "V",
                                            Int::class.javaPrimitiveType,
                                            setting,
                                        )!!
                                        .invoke(settings, value, backSetting)
                                    rt.report("VIDEO: disabled; restored native 30 fps selection")
                                } catch (error: Throwable) {
                                    rt.fail(error)
                                } finally {
                                    rawSelection.remove()
                                }
                            }
                        )
                    } catch (error: Throwable) {
                        rt.fail(error)
                    }
                }
            },
        )
        val videoMaker =
            Class.forName("com.samsung.android.camera.core2.maker.VideoMaker", false, l)
        val highRateMaker =
            Class.forName(
                "com.samsung.android.camera.core2.maker.ProSlowMotionVideoMaker",
                false,
                l,
            )
        // Use the same high-speed request scheduler as native Pro Video at 120 fps.
        // Selecting the class here also lets Samsung replace it when returning to 30/60.
        ModernHook.findAndHookMethod(
            Class.forName("u8.g", false, l),
            "b",
            Int::class.javaPrimitiveType,
            Class.forName("z8.e1", false, l),
            object : ModernHook.Callback() {
                override fun afterHookedMethod(p: ModernHook.Param) {
                    if (
                        p.hasThrowable() ||
                            p.getResult() !== videoMaker ||
                            p.args[0] as Int != 1 ||
                            !rt.enabled(ConfigProvider.VIDEO)
                    )
                        return
                    try {
                        val selected =
                            Reflect.method(resolution!!, "j", Int::class.javaPrimitiveType)!!
                                .invoke(null, camera.backVideoResolution())
                        if (supported(selected)) {
                            rt.guard.arm()
                            p.setResult(highRateMaker)
                            rt.report("VIDEO: native 120 fps request scheduler selected")
                        }
                    } catch (e: Throwable) {
                        rt.fail(e)
                    }
                }
            },
        )
        // The normal-video presenter unconditionally adds a JPEG snapshot stream.
        // Clear only that stream after its native preparation at 120 fps; returning
        // null from the size utility would break the presenter's own calculations.
        ModernHook.findAndHookMethod(
            Class.forName("re.e", false, l),
            "Y0",
            Class.forName("l9.x", false, l),
            Class.forName("u8.e", false, l),
            Class.forName("z8.l0", false, l),
            object : ModernHook.Callback() {
                override fun afterHookedMethod(p: ModernHook.Param) {
                    if (p.hasThrowable() || !rt.enabled(ConfigProvider.VIDEO) || camera.mode() != 1)
                        return
                    try {
                        val selected =
                            Reflect.method(resolution!!, "j", Int::class.javaPrimitiveType)!!
                                .invoke(null, camera.backVideoResolution())
                        if (supported(selected)) {
                            rt.guard.arm()
                            Reflect.field(p.args[2]!!.javaClass, "o")!!.set(p.args[2], null)
                        }
                    } catch (e: Throwable) {
                        rt.fail(e)
                    }
                }
            },
        )
        for (method in arrayOf<String>("d1", "Z0")) {
            ModernHook.findAndHookMethod(
                util,
                method,
                String::class.java,
                resolution,
                object : ModernHook.Callback() {
                    override fun afterHookedMethod(p: ModernHook.Param) {
                        if (
                            p.hasThrowable() ||
                                !rt.enabled(ConfigProvider.VIDEO) ||
                                ("video" != p.args[0])
                        )
                            return
                        try {
                            if (supported(p.args[1]!!)) {
                                rt.guard.arm()
                                p.setResult(true)
                            }
                        } catch (e: Throwable) {
                            rt.fail(e)
                        }
                    }
                },
            )
        }
        ModernHook.findAndHookMethod(
            util,
            "k0",
            object : ModernHook.Callback() {
                override fun afterHookedMethod(p: ModernHook.Param) {
                    if (p.hasThrowable() || !rt.enabled(ConfigProvider.VIDEO)) return
                    try {
                        val values =
                            ArrayList<Any?>(Arrays.asList<Any?>(*(p.getResult() as Array<Any?>)))
                        for (name in
                            arrayOf<String>(
                                "RESOLUTION_3840X2160_120FPS",
                                "RESOLUTION_1920X1080_120FPS",
                            )) {
                            val value = Reflect.value(resolution, name)
                            if (supported(value!!) && !values.contains(value)) values.add(value)
                        }
                        values.sortWith(
                            Comparator.comparingInt<Any?>(
                                ToIntFunction { v: Any? ->
                                    try {
                                        return@ToIntFunction Reflect.call(v!!, "e") as Int
                                    } catch (e: Exception) {
                                        throw IllegalStateException(e)
                                    }
                                }
                            )
                        )
                        val array = ReflectArray.newInstance(resolution, values.size)
                        for (i in values.indices) ReflectArray.set(
                            array,
                            i,
                            values.get(i),
                        )
                        rt.guard.arm()
                        p.setResult(array)
                    } catch (e: Throwable) {
                        rt.fail(e)
                    }
                }
            },
        )
        ModernHook.findAndHookMethod(
            commandMap,
            "e",
            commands,
            object : ModernHook.Callback() {
                override fun afterHookedMethod(p: ModernHook.Param) {
                    if (
                        p.hasThrowable() ||
                            !rt.enabled(ConfigProvider.VIDEO) ||
                            ("BACK_CAMCORDER_RESOLUTION_MENU" != p.args[0].toString())
                    )
                        return
                    try {
                        if (!supported(uhd!!)) return
                        val next = ArrayList<Any?>(p.getResult() as MutableList<*>?)
                        if (!next.contains(uhdCommand)) next.add(uhdCommand)
                        p.setResult(next)
                    } catch (e: Throwable) {
                        rt.fail(e)
                    }
                }
            },
        )
        ModernHook.findAndHookMethod(
            commandMap,
            "c",
            commands,
            object : ModernHook.Callback() {
                override fun afterHookedMethod(p: ModernHook.Param) {
                    if (p.args[0] === uhdCommand && camera.mode() == 1) p.setResult(backSetting)
                }
            },
        )
        // A previously saved UHD120 setting remains displayable after the switch is OFF.
        // This mapping does not add the option to the disabled menu or bypass validation.
        ModernHook.findAndHookMethod(
            commandMap,
            "b",
            Int::class.javaPrimitiveType,
            setting,
            object : ModernHook.Callback() {
                override fun beforeHookedMethod(p: ModernHook.Param) {
                    if (p.args[0] as Int == 3 && p.args[1] === backSetting) p.setResult(uhdCommand)
                }
            },
        )
        val ratio = Class.forName("g9.p", false, l)
        val mapping = Class.forName("g9.q", false, l)
        ModernHook.findAndHookMethod(
            mapping,
            "g",
            ratio,
            Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            object : ModernHook.Callback() {
                override fun afterHookedMethod(p: ModernHook.Param) {
                    if (
                        camera.mode() == 1 &&
                            "RATIO_16X9_UHD_120FPS" == p.args[0].toString() &&
                            !(p.args[2] as Boolean)
                    )
                        p.setResult(uhdCommand)
                }
            },
        )
        rt.onChanged(
            Runnable {
                try {
                    // Normal-video caches are separate from photo / Pro / slow-motion lists.
                    for (contract in
                        arrayOf<Array<String?>?>(
                            arrayOf<String?>("a", "s"),
                            arrayOf<String?>("g", "y"),
                        )) {
                        synchronized(Reflect.get(util, contract!![0])!!) {
                            Reflect.field(util, contract!![1])!!.set(null, null)
                        }
                    }
                } catch (e: Throwable) {
                    rt.fail(e)
                }
            }
        )
    }

    @Throws(Exception::class)
    private fun supported(value: Any): Boolean {
        // These two choices have complete normal-mode command mappings. Other
        // native Pro aspect ratios must not enter the normal menu without one.
        if (
            !java.util.Set.of<String?>("RESOLUTION_3840X2160_120FPS", "RESOLUTION_1920X1080_120FPS")
                .contains(value.toString())
        )
            return false
        val raw = Reflect.method(features!!, "a", resolution)!!.invoke(null, value)
        if (raw !is MutableMap<*, *> || !raw.get("value").toString().toBoolean()) return false
        val modes = raw.get("supported-mode").toString()
        return Arrays.asList<String?>(*TextCompatibility.split(modes, "\\s*,\\s*"))
            .contains("pro_video") ||
            Arrays.asList<String?>(*TextCompatibility.split(modes, "\\s*,\\s*")).contains("video")
    }
}
