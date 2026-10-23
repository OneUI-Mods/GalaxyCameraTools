package dev.ryuya.galaxycamera;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Process;

/**
 * Counts confirmed crashes after a module operation, never ordinary reloads or process eviction.
 */
@android.annotation.SuppressLint(
    "ApplySharedPref") // Must survive a host crash immediately after the mutation.
final class CrashGuard {
  private final SharedPreferences prefs;
  private boolean blocked;
  private long armedAt;

  CrashGuard(Context context) {
    prefs = context.getSharedPreferences("galaxycamera.guard", 0);
    blocked = prefs.getBoolean("blocked", false);
    long now = System.currentTimeMillis(), last = prefs.getLong("operation", 0);
    int previous = prefs.getInt("pid", -1);
    if (previous != Process.myPid() && previous > 0 && now - last >= 0 && now - last < 120000) {
      ActivityManager am = context.getSystemService(ActivityManager.class);
      for (ApplicationExitInfo exit :
          am.getHistoricalProcessExitReasons(context.getPackageName(), previous, 4)) {
        int reason = exit.getReason();
        if (exit.getTimestamp() < last || exit.getTimestamp() <= prefs.getLong("consumed", 0))
          continue;
        if (reason == ApplicationExitInfo.REASON_CRASH
            || reason == ApplicationExitInfo.REASON_CRASH_NATIVE
            || reason == ApplicationExitInfo.REASON_ANR) {
          CrashWindow.State window =
              CrashWindow.record(now, prefs.getLong("window", 0), prefs.getInt("count", 0));
          blocked |= window.blocked();
          prefs
              .edit()
              .putInt("count", window.count())
              .putLong("window", window.started())
              .putLong("consumed", exit.getTimestamp())
              .putBoolean("blocked", blocked)
              .commit();
        }
        break;
      }
    }
  }

  synchronized boolean allowed() {
    return !blocked;
  }

  synchronized void reset(String token) {
    if (token.isEmpty() || token.equals(prefs.getString("reset", ""))) return;
    if (!prefs.edit().clear().putString("reset", token).commit()) {
      blocked = true;
      return;
    }
    blocked = false;
    armedAt = 0;
  }

  synchronized void arm() {
    if (blocked) throw new IllegalStateException("Crash protection");
    long now = System.currentTimeMillis();
    if (armedAt != 0 && now >= armedAt && now - armedAt < 30000) return;
    if (!prefs
        .edit()
        .putInt("pid", Process.myPid())
        .putLong("operation", System.currentTimeMillis())
        .commit()) throw new IllegalStateException("Unable to persist guard");
    armedAt = now;
  }
}
