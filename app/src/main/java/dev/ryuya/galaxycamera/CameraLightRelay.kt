package dev.ryuya.galaxycamera

import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import kotlin.jvm.JvmField
import kotlin.jvm.Synchronized

/* Ephemeral, caller-checked mailbox. No camera handle or arbitrary commands cross processes. */
class CameraLightRelay {
    private var available: Boolean = false
    private var enabled: Boolean = false
    private var updatedAt: Long = 0
    private var commandId: Long = 0
    private var commandedAt: Long = 0
    private var requestedEnabled: Boolean = false

    @Synchronized
    fun state(optionEnabled: Boolean): Bundle {
        val state = Bundle()
        state.putBoolean(
            "available",
            optionEnabled &&
                available &&
                SystemClock.elapsedRealtime() - updatedAt < CameraLightRelay.LEASE_MILLIS,
        )
        state.putBoolean("enabled", enabled)
        state.putLong("command_id", commandId)
        state.putLong("commanded_at", commandedAt)
        state.putBoolean("requested_enabled", requestedEnabled)
        return state
    }

    @Synchronized
    fun update(state: Bundle): Boolean {
        val nextAvailable = state.getBoolean("available")
        val nextEnabled: Boolean = nextAvailable && state.getBoolean("enabled")
        val changed: Boolean = nextAvailable != available || nextEnabled != enabled
        available = nextAvailable
        enabled = nextEnabled
        updatedAt = SystemClock.elapsedRealtime()
        return changed
    }

    @Synchronized
    fun request(nextEnabled: Boolean, optionEnabled: Boolean): Boolean {
        if (!state(optionEnabled).getBoolean("available")) return false
        requestedEnabled = nextEnabled
        commandId++
        commandedAt = SystemClock.elapsedRealtime()
        return true
    }

    companion object {
        @JvmField val STATE_URI: Uri? = Uri.withAppendedPath(ConfigProvider.URI, "light_state")
        @JvmField val COMMAND_URI: Uri? = Uri.withAppendedPath(ConfigProvider.URI, "light_command")
        private const val LEASE_MILLIS: Long = 3000
    }
}
