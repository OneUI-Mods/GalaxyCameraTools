package dev.ryuya.galaxycamera;

import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;

/** Ephemeral, caller-checked mailbox. No camera handle or arbitrary commands cross processes. */
final class CameraLightRelay {
  static final Uri STATE_URI = Uri.withAppendedPath(ConfigProvider.URI, "light_state");
  static final Uri COMMAND_URI = Uri.withAppendedPath(ConfigProvider.URI, "light_command");
  private static final long LEASE_MILLIS = 3000;
  private boolean available;
  private boolean enabled;
  private long updatedAt;
  private long commandId;
  private long commandedAt;
  private boolean requestedEnabled;

  synchronized Bundle state(boolean optionEnabled) {
    Bundle state = new Bundle();
    state.putBoolean(
        "available",
        optionEnabled && available && SystemClock.elapsedRealtime() - updatedAt < LEASE_MILLIS);
    state.putBoolean("enabled", enabled);
    state.putLong("command_id", commandId);
    state.putLong("commanded_at", commandedAt);
    state.putBoolean("requested_enabled", requestedEnabled);
    return state;
  }

  synchronized boolean update(Bundle state) {
    boolean nextAvailable = state.getBoolean("available");
    boolean nextEnabled = nextAvailable && state.getBoolean("enabled");
    boolean changed = nextAvailable != available || nextEnabled != enabled;
    available = nextAvailable;
    enabled = nextEnabled;
    updatedAt = SystemClock.elapsedRealtime();
    return changed;
  }

  synchronized boolean request(boolean nextEnabled, boolean optionEnabled) {
    if (!state(optionEnabled).getBoolean("available")) return false;
    requestedEnabled = nextEnabled;
    commandId++;
    commandedAt = SystemClock.elapsedRealtime();
    return true;
  }
}
