package dev.ryuya.galaxycamera

import java.util.ArrayDeque
import java.util.ArrayList
import java.util.concurrent.Executor
import java.util.function.Consumer
import org.junit.Assert.*
import org.junit.Test

open class ZoomRequestQueueTest {
    @Test
    open fun pendingFramesCollapseToTheLatestValueAndFinishAfterItIsWritten() {
        val tasks = ArrayDeque<Runnable?>()
        val writes = ArrayList<Int?>()
        val finished = ArrayList<Boolean?>()
        val queue =
            ZoomRequestQueue(
                Executor { e -> tasks.add(e) },
                ZoomRequestQueue.Writer { e: Int -> writes.add(e) },
                Runnable { finished.add(true) },
                Consumer { error: Exception? -> fail(error.toString()) },
            )
        queue.submit(1000, false)
        queue.submit(1500, false)
        queue.submit(3000, true)
        assertEquals(1, tasks.size)
        assertTrue(finished.isEmpty())
        tasks.remove()!!.run()
        assertEquals(java.util.List.of(3000), writes)
        assertEquals(java.util.List.of(true), finished)
        assertTrue(tasks.isEmpty())
    }

    @Test
    open fun cancelledMotionCannotWriteItsQueuedFrame() {
        val tasks = ArrayDeque<Runnable?>()
        val writes = ArrayList<Int?>()
        val queue =
            ZoomRequestQueue(
                Executor { e -> tasks.add(e) },
                ZoomRequestQueue.Writer { e: Int -> writes.add(e) },
                Runnable { fail("Cancelled motion completed") },
                Consumer { error: Exception? -> fail(error.toString()) },
            )
        queue.submit(5000, true)
        queue.cancel()
        tasks.remove()!!.run()
        assertTrue(writes.isEmpty())
    }

    @Test
    open fun newFrameArrivingDuringWriteIsAppliedBeforeCompletion() {
        val tasks = ArrayDeque<Runnable?>()
        val writes = ArrayList<Int?>()
        val finished = ArrayList<Boolean?>()
        val holder = arrayOfNulls<ZoomRequestQueue>(1)
        holder[0] =
            ZoomRequestQueue(
                Executor { e -> tasks.add(e) },
                ZoomRequestQueue.Writer { value: Int ->
                    writes.add(value)
                    if (value == 1000) holder[0]!!.submit(3000, true)
                },
                Runnable { finished.add(true) },
                Consumer { error: Exception? -> fail(error.toString()) },
            )
        holder[0]!!.submit(1000, false)
        tasks.remove()!!.run()
        assertTrue(finished.isEmpty())
        assertEquals(1, tasks.size)
        tasks.remove()!!.run()
        assertEquals(java.util.List.of(1000, 3000), writes)
        assertEquals(java.util.List.of(true), finished)
    }

    @Test
    open fun writerFailureStopsFurtherUpdatesAndReportsOnce() {
        val tasks = ArrayDeque<Runnable?>()
        val errors = ArrayList<Exception?>()
        val queue =
            ZoomRequestQueue(
                Executor { e -> tasks.add(e) },
                ZoomRequestQueue.Writer { value: Int ->
                    throw Exception("unavailable")
                },
                Runnable { fail("Failed motion completed") },
                Consumer { e -> errors.add(e) },
            )
        queue.submit(1000, false)
        tasks.remove()!!.run()
        queue.submit(3000, true)
        assertEquals(1, errors.size)
        assertTrue(tasks.isEmpty())
    }
}
