package dev.ryuya.galaxycamera

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import dev.ryuya.galaxycamera.compat.DexKitConfig
import dev.ryuya.galaxycamera.compat.DexSymbols
import dev.ryuya.galaxycamera.startup.FirstUnlockGate
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

class HookEntry : XposedModule() {
    private val firstUnlock = FirstUnlockGate()
    private val main = Handler(Looper.getMainLooper())
    private var process: String? = null
    private var runtime: HostRuntime? = null
    @Volatile private var reconnectState: Array<*>? = null

    override fun onModuleLoaded(p: ModuleLoadedParam) {
        ModernHook.attach(this)
        process = p.processName
        ModernHook.log("Module loaded: $process")
    }

    override fun onPackageReady(p: PackageReadyParam) {
        val name = p.packageName
        if (!p.isFirstPackage || name != process) return
        val loader = p.classLoader
        ModernHook.findAndHookMethod(
            Application::class.java,
            "attach",
            Context::class.java,
            object : ModernHook.Callback() {
                private var attached = false

                override fun afterHookedMethod(call: ModernHook.Param) {
                    val context = call.args[0] as? Context ?: return
                    if (attached || call.hasThrowable() || name != context.packageName) return
                    attached = true
                    firstUnlock.run(context) {
                        DexSymbols.whenPrepared(context) {
                            runCatching { install(context, loader) }
                                .onFailure { ModernHook.log("Host feature unavailable: $it") }
                        }
                    }
                }
            },
        )
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean =
        MainThreadReload.run(main) handOff@{
            val current = runtime
            if (current == null) {
                val pending = reconnectState ?: return@handOff false
                param.setSavedInstanceState(pending)
            } else {
                val accepted = ModernHook.tryRetire {
                    current.prepareRetirement {
                        param.setSavedInstanceState(arrayOf(current.context, current.loader))
                    }
                }
                if (!accepted) return@handOff false
                current.finishRetirement()
                runtime = null
            }
            DexSymbols.detachObserver()
            reconnectState = null
            true
        }

    override fun onHotReloaded(param: HotReloadedParam) {
        val state = requireNotNull(param.savedInstanceState as? Array<*>)
        val context = requireNotNull(state.getOrNull(0) as? Context)
        val loader = requireNotNull(state.getOrNull(1) as? ClassLoader)
        check(param.processName == context.packageName)
        onModuleLoaded(param)
        reconnectState = state
        check(MainThreadReload.run(main) {
            if (context.packageName in DexKitConfig.HOSTS) {
                check(DexSymbols.canInstallNow(context)) { "Camera discovery is still preparing" }
            }
            check(install(context, loader)) { "Camera host contract is unavailable" }
            param.oldHookHandles.forEach { it.unhook() }
            reconnectState = null
            ModernHook.log("ONEUI_MODS_HOT_RELOAD package=${BuildConfig.APPLICATION_ID} " +
                "version=${BuildConfig.VERSION_CODE} pid=${Process.myPid()}")
            true
        }) { "Camera host main thread was unavailable" }
    }

    private fun install(context: Context, loader: ClassLoader): Boolean {
        val current = HostRuntime(context, loader)
        runtime = current
        try {
            if (Build.VERSION.SDK_INT != 37 || Build.MODEL != "SM-S948Q" ||
                Build.DISPLAY != "CP2A.260605.016.S948QOPU1BZI9"
            ) {
                current.report("UNSUPPORTED: device / firmware")
                return false
            }
            if (!current.guard.allowed()) {
                current.ready()
                return false
            }
            val name = context.packageName
            val version = context.packageManager.getPackageInfo(name, 0).versionName
            when {
                name == ConfigProvider.CAMERA &&
                    (version == "17.0.00.43" || DexSymbols.provenTarget(loader)) ->
                    CameraHooks17(current).install()
                name == ConfigProvider.ASSISTANT &&
                    (version == "5.0.00.7" || DexSymbols.provenTarget(loader)) ->
                    AssistantUi17(current).install()
                name == ConfigProvider.EXPERT &&
                    (version == "6.0.02.8" || DexSymbols.provenTarget(loader)) ->
                    ExpertRawHooks17(current).install()
                name == ConfigProvider.SYSTEM_UI -> QuickPanelLight17(current).install()
                name !in ConfigProvider.HOSTS.orEmpty() -> Camera2Unlock17(current).install()
                else -> {
                    current.report("UNSUPPORTED: application version $version")
                    return false
                }
            }
            return true
        } catch (error: Throwable) {
            current.fail(error)
            throw error
        }
    }
}
