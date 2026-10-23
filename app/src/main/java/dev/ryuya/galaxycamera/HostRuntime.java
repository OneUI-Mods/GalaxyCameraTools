package dev.ryuya.galaxycamera;

import android.content.Context;
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

  HostRuntime(Context c, ClassLoader l) {
    context = c;
    loader = l;
    HandlerThread thread =
        new HandlerThread("GalaxyCameraTools", android.os.Process.THREAD_PRIORITY_BACKGROUND);
    thread.start();
    worker = new Handler(thread.getLooper());
    guard = new CrashGuard(c);
    load();
    c.getContentResolver()
        .registerContentObserver(
            ConfigProvider.URI,
            false,
            new ContentObserver(worker) {
              public void onChange(boolean own) {
                load();
              }
            });
  }

  private void load() {
    try {
      Bundle b = context.getContentResolver().call(ConfigProvider.URI, "get", null, null);
      if (b == null) return;
      options = b;
      guard.reset(b.getString("reset", ""));
      for (Runnable listener : changed) main.post(listener);
    } catch (RuntimeException e) {
      failed = true;
      ModernHook.log("Settings unavailable: " + e.getClass().getSimpleName());
    }
  }

  boolean enabled(String key) {
    return !failed && guard.allowed() && options.getBoolean(key, false);
  }

  void onChanged(Runnable listener) {
    changed.add(listener);
  }

  void report(String status) {
    worker.post(
        () -> {
          if (status.equals(lastStatus)) return;
          lastStatus = status;
          try {
            context.getContentResolver().call(ConfigProvider.URI, "status", status, null);
          } catch (RuntimeException ignored) {
          }
        });
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
