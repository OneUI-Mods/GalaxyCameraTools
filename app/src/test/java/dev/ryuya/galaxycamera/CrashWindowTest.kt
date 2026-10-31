package dev.ryuya.galaxycamera

import org.junit.Assert.*
import org.junit.Test

open class CrashWindowTest {
    @Test
    open fun thirdConfirmedCrashInWindowBlocks() {
        val a = CrashWindow.record(1000, 0, 0)
        val b = CrashWindow.record(10000, a!!.started, a!!.count)
        assertFalse(b!!.blocked())
        assertTrue(CrashWindow.record(120999, b.started, b.count)!!.blocked())
    }

    @Test
    open fun spacedCrashesDoNotTurnIntoASlidingWindow() {
        val a = CrashWindow.record(1000, 0, 0)
        val b = CrashWindow.record(120000, a!!.started, a!!.count)
        val c = CrashWindow.record(239000, b!!.started, b!!.count)
        assertEquals(1, c!!.count)
        assertFalse(c!!.blocked())
    }

    @Test
    open fun clockRollbackAndExactExpiryStartANewWindow() {
        assertEquals(1, CrashWindow.record(900, 1000, 2)!!.count)
        assertEquals(1, CrashWindow.record(121000, 1000, 2)!!.count)
    }
}
