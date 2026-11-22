package dev.ryuya.galaxycamera

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import android.os.Trace
import dev.ryuya.galaxycamera.compat.DexSymbols

/** Removes empty registrations from Samsung Camera's foreground lifecycle observer. */
internal class CameraResumeReceivers17(private val runtime: HostRuntime) {
    fun install(camera: Class<*>) {
        @Suppress("DEPRECATION")
        val version = runtime.context.packageManager.getPackageInfo(runtime.context.packageName, 0).versionName
        if (version != "17.0.00.43") return

        val observer = DexSymbols.findClass("w7.j", false, runtime.loader)
        val owner = observer.declaredFields.single { it.type == camera }.apply { isAccessible = true }
        val resume = observer.declaredMethods.single {
            it.name == "onResume" && it.parameterTypes.singleOrNull()?.name == "androidx.lifecycle.LifecycleOwner"
        }
        // The scope is the stock observer on the camera's main thread, never another caller.
        val registeringFor = ThreadLocal<Activity>()
        val registrations = ArrayList<ModernHook.Handle>(3)
        try {
            registrations += ModernHook.aroundMethod(resume) { chain ->
                val activity = owner.get(chain.thisObject) as? Activity
                if (activity?.javaClass !== camera || activity.intent?.action != Intent.ACTION_MAIN ||
                    Looper.myLooper() !== Looper.getMainLooper() || !runtime.guard.allowed()
                ) {
                    return@aroundMethod chain.proceed()
                }
                val previous = registeringFor.get()
                registeringFor.set(activity)
                Trace.beginSection("GalaxyCameraTools camera resume receivers")
                try {
                    chain.proceed()
                } finally {
                    Trace.endSection()
                    if (previous == null) registeringFor.remove() else registeringFor.set(previous)
                }
            }
            val parameters = arrayOf(BroadcastReceiver::class.java, IntentFilter::class.java)
            val wrapper = ContextWrapper::class.java
            for (method in listOf(
                wrapper.getDeclaredMethod("registerReceiver", *parameters),
                wrapper.getDeclaredMethod("registerReceiver", *parameters, Int::class.javaPrimitiveType),
            )) {
                registrations += ModernHook.aroundMethod(method) { chain ->
                    val activity = registeringFor.get()
                    val filter = chain.args[1] as? IntentFilter
                    if (activity != null && chain.thisObject === activity && chain.args[0] is BroadcastReceiver &&
                        filter != null && filter.countActions() == 0 && filter.countCategories() == 0 &&
                        filter.countDataSchemes() == 0 && filter.countDataTypes() == 0 &&
                        filter.countDataAuthorities() == 0 && filter.countDataPaths() == 0 &&
                        filter.countDataSchemeSpecificParts() == 0
                    ) {
                        // The resolver has no action, MIME type or scheme under which to index
                        // this filter. Filters containing real shutdown, Knox and camera events
                        // still take the stock path, including all of their original flags.
                        return@aroundMethod null
                    }
                    chain.proceed()
                }
            }
        } catch (error: Throwable) {
            registrations.forEach(ModernHook.Handle::unhook)
            throw error
        }
        ModernHook.log("CAMERA_RESUME_RECEIVERS_READY")
    }
}
