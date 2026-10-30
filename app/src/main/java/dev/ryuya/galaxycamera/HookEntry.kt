package dev.ryuya.galaxycamera

import android.app.Application
import android.content.Context
import android.os.Build
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import kotlin.jvm.JvmField
import kotlin.math.log

class HookEntry : XposedModule() {
    private var process: String? = null

    override fun onModuleLoaded(p: ModuleLoadedParam) {
        ModernHook.attach(this)
        process = p.getProcessName()
        ModernHook.log("Module loaded: " + process)
    }

    override fun onPackageReady(p: PackageReadyParam) {
        val name = p.getPackageName()
        ModernHook.log("Package ready: " + name + "; first=" + p.isFirstPackage())
        if (!p.isFirstPackage() || name != process) return
        ModernHook.findAndHookMethod(
            Application::class.java,
            "attach",
            Context::class.java,
            object : ModernHook.Callback() {
                @JvmField var attached: Boolean = false

                override fun afterHookedMethod(call: ModernHook.Param) {
                    if (attached || call.hasThrowable()) return
                    val c: Context? = call.args[0] as Context?
                    var runtime: HostRuntime? = null
                    if (name != c!!.getPackageName()) return
                    attached = true
                    try {
                        val rt = HostRuntime(c!!, p.getClassLoader())
                        runtime = rt
                        if (
                            Build.VERSION.SDK_INT != 37 ||
                                ("SM-S948Q" != Build.MODEL) ||
                                ("CP2A.260605.016.S948QOPU1BZI9" != Build.DISPLAY)
                        ) {
                            rt.report("UNSUPPORTED: device / firmware")
                            return
                        }
                        if (!rt.guard.allowed()) {
                            rt.ready()
                            return
                        }
                        val version = c!!.getPackageManager().getPackageInfo(name, 0).versionName
                        if (name == ConfigProvider.CAMERA && "17.0.00.43" == version)
                            CameraHooks17(rt).install()
                        else if (name == ConfigProvider.ASSISTANT && "5.0.00.7" == version)
                            AssistantUi17(rt).install()
                        else if (name == ConfigProvider.EXPERT && "6.0.02.8" == version)
                            ExpertRawHooks17(rt).install()
                        else if (name == ConfigProvider.SYSTEM_UI) QuickPanelLight17(rt).install()
                        else if (!ConfigProvider.HOSTS!!.contains(name))
                            Camera2Unlock17(rt).install()
                        else rt.report("UNSUPPORTED: application version " + version)
                    } catch (e: Throwable) {
                        if (runtime != null) runtime.fail(e)
                        else ModernHook.log("Host feature unavailable: " + e)
                    }
                }
            },
        )
    }
}
