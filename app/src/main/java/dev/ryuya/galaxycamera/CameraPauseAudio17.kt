package dev.ryuya.galaxycamera

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Trace
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicReference

/** Runs Samsung's voice shutter cleanup alongside the transition on the camera worker. */
internal class CameraPauseAudio17(private val runtime: HostRuntime) {
    private class VoiceStop(
        val voice: Any,
        action: Callable<Unit>,
        private val onDone: (VoiceStop) -> Unit,
    ) : FutureTask<Unit>(action) {
        override fun done() = onDone(this)
    }

    fun install(camera: Class<*>) {
        @Suppress("DEPRECATION")
        val version = runtime.context.packageManager.getPackageInfo(runtime.context.packageName, 0).versionName
        if (version != "17.0.00.43") return

        val controller = camera.getDeclaredField("u").apply { isAccessible = true }
        check(controller.type.name == "w7.n0")
        val recognizer = controller.type.getDeclaredField("d").apply { isAccessible = true }
        check(recognizer.type.name == "com.samsung.android.speech.SemSpeechRecognizer")
        val startRecognition = controller.type.getDeclaredMethod("d")
        val stopRecognition = controller.type.getDeclaredMethod("e").apply { isAccessible = true }
        val workerField = camera.getDeclaredField("K").apply { isAccessible = true }
        check(workerField.type == Handler::class.java && stopRecognition.returnType == Void.TYPE)

        val main = Looper.getMainLooper()
        val pending = AtomicReference<VoiceStop>()
        val insideWorkerStop = ThreadLocal.withInitial { false }
        var losingFocus: Activity? = null
        var pausing: Activity? = null

        fun eligible(activity: Activity) = activity.javaClass === camera &&
            activity.intent?.action == Intent.ACTION_MAIN && !activity.isInMultiWindowMode &&
            Looper.myLooper() === main && runtime.guard.allowed()

        fun schedule(activity: Activity, voice: Any): Boolean {
            val worker = workerField.get(activity) as? Handler ?: return false
            if (worker.looper === main || recognizer.get(voice) == null) return false
            val previous = pending.get()
            if (previous != null) return previous.voice === voice

            val task = VoiceStop(voice, Callable<Unit> {
                Trace.beginSection("GalaxyCameraTools stop voice shutter")
                insideWorkerStop.set(true)
                try {
                    stopRecognition.invoke(voice)
                } catch (error: ReflectiveOperationException) {
                    ModernHook.log("CAMERA_PAUSE_VOICE_FALLBACK ${error.javaClass.simpleName}")
                    runtime.main.post {
                        if (!activity.isDestroyed && !activity.hasWindowFocus()) {
                            runCatching { stopRecognition.invoke(voice) }
                        }
                    }
                } finally {
                    insideWorkerStop.remove()
                    Trace.endSection()
                }
                Unit
            }) { pending.compareAndSet(it, null) }
            if (!pending.compareAndSet(null, task)) return false
            if (worker.post(task)) return true
            pending.compareAndSet(task, null)
            return false
        }

        val installed = ArrayList<ModernHook.Handle>(5)
        try {
            installed += ModernHook.aroundMethod(startRecognition) { chain ->
                // If focus returns before the worker stops, finish that stop before
                // the native recognizer checks whether it is already running.
                val queued = pending.get()
                if (queued != null && queued.voice === chain.thisObject && !insideWorkerStop.get()) {
                    queued.run()
                    try {
                        queued.get()
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    } catch (_: ExecutionException) {
                        ModernHook.log("CAMERA_PAUSE_VOICE_WORKER_FAILED")
                    }
                }
                chain.proceed()
            }
            installed += ModernHook.aroundMethod(stopRecognition) { chain ->
                val activity = losingFocus
                val voice = chain.thisObject
                if (Looper.myLooper() === main && !insideWorkerStop.get() && activity != null && voice != null) {
                    val queued = runCatching { controller.get(activity) === voice && schedule(activity, voice) }
                    if (queued.getOrDefault(false)) return@aroundMethod null
                }
                chain.proceed()
            }
            val focusChanged = camera.getDeclaredMethod("onWindowFocusChanged", Boolean::class.javaPrimitiveType)
            installed += ModernHook.aroundMethod(focusChanged) { chain ->
                val activity = chain.thisObject as? Activity
                if (activity == null || chain.args[0] != false || !eligible(activity)) {
                    return@aroundMethod chain.proceed()
                }
                losingFocus = activity
                try {
                    chain.proceed()
                } finally {
                    losingFocus = null
                }
            }
            installed += ModernHook.aroundMethod(camera.getDeclaredMethod("z0")) { chain ->
                val activity = pausing
                if (activity != null && chain.thisObject === activity && Looper.myLooper() === main) {
                    runCatching { controller.get(activity)?.let { schedule(activity, it) } }
                }
                chain.proceed()
            }
            installed += ModernHook.aroundMethod(camera.getDeclaredMethod("onPause")) { chain ->
                val activity = chain.thisObject as? Activity
                if (activity == null || !eligible(activity)) return@aroundMethod chain.proceed()
                // onPause joins the camera worker and then calls native stop again.
                pausing = activity
                try {
                    chain.proceed()
                } finally {
                    pausing = null
                }
            }
        } catch (error: Throwable) {
            installed.forEach(ModernHook.Handle::unhook)
            throw error
        }
        runtime.onReloadReady { pending.get() == null }
        ModernHook.log("CAMERA_PAUSE_VOICE_PARALLEL_READY")
    }
}
