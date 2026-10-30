package dev.ryuya.galaxycamera

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.SharedPreferences
import android.os.Process
import kotlin.jvm.Synchronized

/*
 * Counts confirmed crashes after a module operation, never ordinary reloads or process eviction.
 */
@SuppressLint("ApplySharedPref") // Must survive a host crash immediately after the mutation.
class CrashGuard(context: Context) {
    private val prefs: SharedPreferences
    private var blocked: Boolean
    private var armedAt: Long = 0

    init {
        prefs = context.getSharedPreferences("galaxycamera.guard", 0)
        blocked = prefs.getBoolean("blocked", false)
        val now = System.currentTimeMillis()
        val last = prefs.getLong("operation", 0)
        val previous = prefs.getInt("pid", -1)
        if (previous != Process.myPid() && previous > 0 && now - last >= 0 && now - last < 120000) {
            val am = context.getSystemService<ActivityManager?>(ActivityManager::class.java)
            for (exit in
                am.getHistoricalProcessExitReasons(context.getPackageName(), previous, 4)) {
                val reason = exit.getReason()
                if (
                    exit.getTimestamp() < last ||
                        exit.getTimestamp() <= prefs.getLong("consumed", 0)
                )
                    continue
                if (
                    reason == ApplicationExitInfo.REASON_CRASH ||
                        reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                        reason == ApplicationExitInfo.REASON_ANR
                ) {
                    val window =
                        CrashWindow.record(
                            now,
                            prefs.getLong("window", 0),
                            prefs.getInt("count", 0),
                        )
                    blocked = blocked or window!!.blocked()
                    prefs
                        .edit()
                        .putInt("count", window.count)
                        .putLong("window", window.started)
                        .putLong("consumed", exit.getTimestamp())
                        .putBoolean("blocked", blocked)
                        .commit()
                }
                break
            }
        }
    }

    @Synchronized
    fun allowed(): Boolean {
        return !blocked
    }

    @Synchronized
    fun reset(token: String) {
        if (token.isEmpty() || token == prefs.getString("reset", "")) return
        if (!prefs.edit().clear().putString("reset", token).commit()) {
            blocked = true
            return
        }
        blocked = false
        armedAt = 0
    }

    @Synchronized
    fun arm() {
        check(!blocked) { "Crash protection" }
        val now = System.currentTimeMillis()
        if (armedAt != 0L && now >= armedAt && now - armedAt < 30000) return
        check(
            prefs
                .edit()
                .putInt("pid", Process.myPid())
                .putLong("operation", System.currentTimeMillis())
                .commit()
        ) {
            "Unable to persist guard"
        }
        armedAt = now
    }
}
