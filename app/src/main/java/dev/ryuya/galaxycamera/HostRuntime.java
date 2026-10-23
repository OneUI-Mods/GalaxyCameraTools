package dev.ryuya.galaxycamera;

import android.content.Context;
import android.content.Intent;
import android.content.ComponentName;
import android.app.BroadcastOptions;
import android.database.ContentObserver;
import android.os.*;
import java.util.concurrent.CopyOnWriteArrayList;

/** One configuration reader per host, with no Binder work in per-frame callbacks. */
final class HostRuntime {
  final Context context;
  final ClassLoader loader;
  final Handler main = new Handler(Looper.getMainLooper());
  final Handler worker;
  final CrashGuard guard;
  private volatile Bundle options = Bundle.EMPTY;
  private volatile boolean failed;
  private final CopyOnWriteArrayList<Runnable> changed = new CopyOnWriteArrayList<>();
  private String lastStatus = "";
  private volatile boolean available;
  private boolean observing;
  private int attempts;

  HostRuntime(Context c, ClassLoader l) {
    context = c;
    loader = l;
    HandlerThread thread =
        new HandlerThread("GalaxyCameraTools", android.os.Process.THREAD_PRIORITY_BACKGROUND);
    thread.start();
    worker = new Handler(thread.getLooper());
    guard = new CrashGuard(c);
    // The first preview request can arrive before a background read completes.
    // Read once before installing changes; visibility retries remain asynchronous.
    load();
  }

  boolean available() {
    return available;
  }

  private void load() {
    try {
      Bundle b = context.getContentResolver().call(ConfigProvider.URI, "get", null, null);
      if (b == null) throw new IllegalStateException("Missing configuration");
      options = b;
      guard.reset(b.getString("reset", ""));
      available = true;
      if (!observing) {
        context
            .getContentResolver()
            .registerContentObserver(
                ConfigProvider.URI,
                false,
                new ContentObserver(worker) {
                  public void onChange(boolean own) {
                    load();
                  }
                });
        observing = true;
      }
      for (Runnable listener : changed) main.post(listener);
      if (!lastStatus.isEmpty()) sendStatus(lastStatus);
    } catch (RuntimeException e) {
      available = false;
      // A scoped host need not declare this provider in its original manifest.
      // Ask our explicit receiver to grant visibility, then retry off the UI thread.
      if (attempts++ < 6) {
        Intent request =
            new Intent()
                .setComponent(
                    new ComponentName(
                        BuildConfig.APPLICATION_ID,
                        BuildConfig.APPLICATION_ID + ".ConfigurationAccessReceiver"));
        context.sendBroadcast(
            request, null, BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
        worker.postDelayed(this::load, Math.min(200L << attempts, 5000L));
      } else ModernHook.log("Settings unavailable: " + e.getClass().getSimpleName());
    }
  }

  boolean enabled(String key) {
    return available && !failed && guard.allowed() && options.getBoolean(key, false);
  }

  boolean configured(String key) {
    return available && options.getBoolean(key, false);
  }

  void onChanged(Runnable listener) {
    changed.add(listener);
  }

  void report(String status) {
    worker.post(
        () -> {
          if (status.equals(lastStatus)) return;
          lastStatus = status;
          if (available) sendStatus(status);
        });
  }

  private void sendStatus(String status) {
    try {
      context.getContentResolver().call(ConfigProvider.URI, "status", status, null);
    } catch (RuntimeException ignored) {
    }
  }

  void fail(Throwable error) {
    failed = true;
    report("ERROR: " + error.getClass().getSimpleName());
    ModernHook.log("Feature disabled in this process: " + error);
  }

  void ready() {
    report(guard.allowed() ? "READY" : "BLOCKED: crash protection");
  }
}
