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
import android.os.Message
import android.net.Uri
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import android.os.Process
import dev.ryuya.galaxycamera.compat.DexSymbols
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmField
import kotlin.math.log

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
    private val thread = HandlerThread("GalaxyCameraTools", Process.THREAD_PRIORITY_BACKGROUND)
    private val workerActivity = ReentrantLock()
    private val observers = ArrayList<ContentObserver>()
    private val readiness = ArrayList<() -> Boolean>()
    private val cleanup = ArrayList<() -> Unit>()
    private var observing: Boolean = false
    @Volatile private var retired = false

    fun onReloadReady(test: () -> Boolean) { readiness.add(test) }
    fun onRetire(action: () -> Unit) { cleanup.add(action) }

    @Synchronized
    fun observe(uri: Uri, action: () -> Unit) {
        if (retired) return
        val observer = object : ContentObserver(worker) {
            override fun onChange(own: Boolean) {
                if (!retired) action()
            }
        }
        context.contentResolver.registerContentObserver(uri, false, observer)
        observers.add(observer)
    }

    fun prepareRetirement(save: () -> Unit): Boolean {
        if (!workerActivity.tryLock()) return false
        return try {
            if (!readiness.all { it() }) return false
            save()
            retired = true
            true
        } finally {
            workerActivity.unlock()
        }
    }

    fun finishRetirement() {
        worker.removeCallbacksAndMessages(null)
        main.removeCallbacksAndMessages(null)
        thread.quit()
        for (observer in observers) {
            runCatching { context.contentResolver.unregisterContentObserver(observer) }
                .onFailure { ModernHook.log("Camera observer cleanup failed: ${it.javaClass.simpleName}") }
        }
        observers.clear()
        changed.clear()
        cleanup.asReversed().forEach { runCatching(it) }
        cleanup.clear()
    }
    private val retries = ConfigurationRetries()
    private val retry = Runnable { load() }

    init {
        context = c
        DexSymbols.configure(c)
        loader = l
        thread.start()
        worker = object : Handler(thread.looper) {
            override fun dispatchMessage(message: Message) {
                workerActivity.withLock {
                    if (!retired) super.dispatchMessage(message)
                }
            }
        }
        guard = CrashGuard(c)
        // The first preview request can arrive before a background read completes.
        // Read once before installing changes; visibility retries remain asynchronous.
        load()
    }

    fun available(): Boolean {
        return available
    }

    @Synchronized
    private fun load() {
        if (retired) return
        try {
            val b = context.getContentResolver().call(ConfigProvider.URI!!, "get", null, null)
            checkNotNull(b) { "Missing configuration" }
            options = b
            guard.reset(b.getString("reset", ""))
            available = true
            worker.removeCallbacks(retry)
            retries.succeeded()
            if (!observing) {
                observe(requireNotNull(ConfigProvider.URI), ::load)
                observing = true
            }
            for (listener in changed) main.post(listener!!)
            if (!lastStatus.isEmpty()) sendStatus(lastStatus)
        } catch (e: RuntimeException) {
            available = false
            // A scoped host need not declare this provider in its original manifest.
            // Ask our explicit receiver to grant visibility, then retry off the UI thread.
            val delay = retries.nextDelay()
            if (delay != null) {
                val request =
                    Intent()
                        .setComponent(
                            ComponentName(
                                BuildConfig.APPLICATION_ID,
                                BuildConfig.APPLICATION_ID + ".ConfigurationAccessReceiver",
                            ),
                        )
                context.sendBroadcast(
                    request,
                    null,
                    BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle(),
                )
                worker.removeCallbacks(retry)
                worker.postDelayed(retry, delay)
            } else ModernHook.log("Settings unavailable: " + e.javaClass.getSimpleName())
        }
    }

    fun enabled(key: String?): Boolean {
        return !retired && available && !failed && guard.allowed() && options!!.getBoolean(key, false)
    }

    fun configured(key: String?): Boolean {
        return available && options!!.getBoolean(key, false)
    }

    fun onChanged(listener: Runnable?) {
        changed.add(listener)
    }

    fun report(status: String) {
        if (retired || status == submittedStatus.getAndSet(status)) return
        worker.post(
            Runnable {
                if (status == lastStatus) return@Runnable
                lastStatus = status
                if (available) sendStatus(status)
            },
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
