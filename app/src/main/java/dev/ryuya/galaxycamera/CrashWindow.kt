package dev.ryuya.galaxycamera

import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic

/* Fixed two-minute windows: normal restarts never enter this ledger. */
object CrashWindow {
    @JvmStatic
    fun record(now: Long, started: Long, count: Int): CrashWindow.State? {
        if (now < started || now - started >= 120000 || count <= 0) return CrashWindow.State(now, 1)
        return CrashWindow.State(started, count + 1)
    }

    @JvmRecord
    data class State(val started: Long, val count: Int) {
        fun blocked(): Boolean {
            return count >= 3
        }

        override fun toString(): String = "State[started=$started, count=$count]"
    }
}
