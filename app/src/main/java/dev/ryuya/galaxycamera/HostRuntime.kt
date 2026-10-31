package dev.ryuya.galaxycamera

import android.app.BroadcastOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmField
import kotlin.math.log
import kotlin.math.min

/* One configuration reader per host, with no Binder work in per-frame callbacks. */
class HostRuntime(c: Context, l: ClassLoader) {
    @JvmField val context: Context
    @JvmField val loader: ClassLoader
    @JvmField val main: Handler = Handler(Looper.getMainLooper())
    @JvmField val worker: Handler
    @JvmField val guard: CrashGuard
    @Volatile private var options: Bundle? = Bundle.EMPTY
    @Volatile private var failed: Boolean = false
    private val changed: CopyOnWriteArrayList<Runnable?> = CopyOnWriteArrayList<Runnable?>()
    private var lastStatus: String = ""
    private val submittedStatus: AtomicReference<String?> = AtomicReference<String?>("")
    @Volatile private var available: Boolean = false
    private var observing: Boolean = false
    private var attempts: Int = 0

    init {
        context = c
        loader = l
        val thread = HandlerThread("GalaxyCameraTools", Process.THREAD_PRIORITY_BACKGROUND)
        thread.start()
        worker = Handler(thread.getLooper())
        guard = CrashGuard(c)
        // The first preview request can arrive before a background read completes.
        // Read once before installing changes; visibility retries remain asynchronous.
        load()
    }

    fun available(): Boolean {
        return available
    }

    private fun load() {
        try {
            val b = context.getContentResolver().call(ConfigProvider.URI!!, "get", null, null)
            checkNotNull(b) { "Missing configuration" }
            options = b
            guard.reset(b.getString("reset", ""))
            available = true
            if (!observing) {
                context
                    .getContentResolver()
                    .registerContentObserver(
                        ConfigProvider.URI!!,
                        false,
                        object : ContentObserver(worker) {
                            override fun onChange(own: Boolean) {
                                load()
                            }
                        },
                    )
                observing = true
            }
            for (listener in changed) main.post(listener!!)
            if (!lastStatus.isEmpty()) sendStatus(lastStatus)
        } catch (e: RuntimeException) {
            available = false
            // A scoped host need not declare this provider in its original manifest.
            // Ask our explicit receiver to grant visibility, then retry off the UI thread.
            if (attempts++ < 6) {
                val request =
                    Intent()
                        .setComponent(
                            ComponentName(
                                BuildConfig.APPLICATION_ID,
                                BuildConfig.APPLICATION_ID + ".ConfigurationAccessReceiver",
                            )
                        )
                context.sendBroadcast(
                    request,
                    null,
                    BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle(),
                )
                worker.postDelayed(Runnable { this.load() }, min(200L shl attempts, 5000L))
            } else ModernHook.log("Settings unavailable: " + e.javaClass.getSimpleName())
        }
    }

    fun enabled(key: String?): Boolean {
        return available && !failed && guard.allowed() && options!!.getBoolean(key, false)
    }

    fun configured(key: String?): Boolean {
        return available && options!!.getBoolean(key, false)
    }

    fun onChanged(listener: Runnable?) {
        changed.add(listener)
    }

    fun report(status: String) {
        if (status == submittedStatus.getAndSet(status)) return
        worker.post(
            Runnable {
                if (status == lastStatus) return@Runnable
                lastStatus = status
                if (available) sendStatus(status)
            }
        )
    }

    private fun sendStatus(status: String?) {
        try {
            context.getContentResolver().call(ConfigProvider.URI!!, "status", status, null)
        } catch (ignored: RuntimeException) {}
    }

    fun fail(error: Throwable) {
        failed = true
        report("ERROR: " + error.javaClass.getSimpleName())
        ModernHook.log("Feature disabled in this process: " + error)
    }

    fun ready() {
        report(if (guard.allowed()) "READY" else "BLOCKED: crash protection")
    }
}
