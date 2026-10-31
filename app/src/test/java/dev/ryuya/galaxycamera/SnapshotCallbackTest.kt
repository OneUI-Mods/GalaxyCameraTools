package dev.ryuya.galaxycamera

import org.junit.Assert.*
import org.junit.Test

class SnapshotCallbackTest {
    interface Listener {
        fun onPreviewSnapshotShow()

        fun unrelated()
    }

    private fun callback(shown: Runnable = Runnable {}): Listener =
        SnapshotCallback.create(
            checkNotNull(Listener::class.java.classLoader),
            Listener::class.java,
            shown,
        ) as Listener

    @Test
    fun zeroArgumentObjectMethodsDoNotCrashOrInvokeTheCallback() {
        var calls = 0
        val value = callback(Runnable { calls++ })
        assertEquals("Galaxy Camera Tools snapshot callback", value.toString())
        assertEquals(System.identityHashCode(value), value.hashCode())
        assertEquals(0, calls)
    }

    @Test
    fun equalsUsesIdentityAndAcceptsNull() {
        val value = callback()
        assertTrue(value.equals(value))
        assertFalse(value.equals(callback()))
        assertFalse(value.equals(null))
    }

    @Test
    fun nativeZeroArgumentCallbackIsDeliveredOnce() {
        var calls = 0
        val value = callback(Runnable { calls++ })
        value.onPreviewSnapshotShow()
        assertEquals(1, calls)
    }

    @Test
    fun otherCallbacksDoNotCompleteTheSnapshot() {
        var calls = 0
        val value = callback(Runnable { calls++ })
        value.unrelated()
        assertEquals(0, calls)
    }
}
