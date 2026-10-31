package dev.ryuya.galaxycamera

import java.util.concurrent.Executor
import java.util.function.Consumer
import kotlin.jvm.Synchronized

/* Keeps one outstanding camera update and replaces its value instead of building a backlog. */
class ZoomRequestQueue(
    executor: Executor,
    writer: ZoomRequestQueue.Writer,
    completed: Runnable,
    failed: Consumer<Exception?>,
) {
    fun interface Writer {
        @Throws(Exception::class) fun apply(value: Int)
    }

    private val executor: Executor
    private val drainTask: Runnable = Runnable { this.drain() }
    private val writer: ZoomRequestQueue.Writer
    private val completed: Runnable
    private val failed: Consumer<Exception?>
    private var latest: Int = 0
    private var revision: Long = 0
    private var scheduled: Boolean = false
    private var cancelled: Boolean = false
    private var finishRequested: Boolean = false

    init {
        this.executor = executor
        this.writer = writer
        this.completed = completed
        this.failed = failed
    }

    @Synchronized
    fun submit(value: Int, finish: Boolean) {
        if (cancelled) return
        latest = value
        revision++
        finishRequested = finish
        if (!scheduled) {
            scheduled = true
            executor.execute(drainTask)
        }
    }

    @Synchronized
    fun cancel() {
        cancelled = true
    }

    private fun drain() {
        val value: Int
        val version: Long
        synchronized(this) {
            if (cancelled) return
            value = latest
            version = revision
        }
        try {
            writer.apply(value)
        } catch (error: Exception) {
            synchronized(this) {
                if (cancelled) return
                cancelled = true
            }
            failed.accept(error)
            return
        }
        val finish: Boolean
        synchronized(this) {
            if (cancelled) return
            if (version != revision) {
                executor.execute(drainTask)
                return
            }
            scheduled = false
            finish = finishRequested
            if (finish) cancelled = true
        }
        if (finish) completed.run()
    }
}
