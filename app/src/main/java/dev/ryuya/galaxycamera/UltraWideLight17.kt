package dev.ryuya.galaxycamera

import dev.ryuya.galaxycamera.compat.DexSymbols

/* Enables Samsung's existing ultra-wide light path without changing saved camera preferences. */
class UltraWideLight17(runtime: HostRuntime) {
    private val rt: HostRuntime

    init {
        rt = runtime
    }

    @Throws(Exception::class)
    fun install() {
        val loader = rt.loader
        val key = DexSymbols.findClass("l9.f0", false, loader)
        val wideLight = Reflect.value(key, "FLASH_IN_WIDE_LENS")
        ModernHook.findAndHookMethod(
            DexSymbols.findClass("vc.j", false, loader),
            "d",
            key,
            object : ModernHook.Callback() {
                override fun afterHookedMethod(p: ModernHook.Param) {
                    if (
                        !p.hasThrowable() &&
                            p.args[0] === wideLight &&
                            rt.enabled(ConfigProvider.LIGHT)
                    ) {
                        try {
                            rt.guard.arm()
                            p.setResult(1)
                        } catch (error: Throwable) {
                            rt.fail(error)
                        }
                    }
                }
            },
        )
        val feature = DexSymbols.findClass("e5.d", false, loader)
        val supported = Reflect.value(feature, "SUPPORT_FLASH_IN_WIDE_LENS")
        ModernHook.findAndHookMethod(
            DexSymbols.findClass("e5.g", false, loader),
            "o",
            feature,
            object : ModernHook.Callback() {
                override fun afterHookedMethod(p: ModernHook.Param) {
                    if (
                        !p.hasThrowable() &&
                            p.args[0] === supported &&
                            rt.enabled(ConfigProvider.LIGHT)
                    ) {
                        try {
                            rt.guard.arm()
                            p.setResult(true)
                        } catch (error: Throwable) {
                            rt.fail(error)
                        }
                    }
                }
            },
        )
    }
}
