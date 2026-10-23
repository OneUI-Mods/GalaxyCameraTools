package dev.ryuya.galaxycamera;

import android.app.Activity;
import android.database.ContentObserver;
import android.hardware.camera2.CaptureRequest;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

/** Receives flashlight requests inside Camera, which owns the active camera device. */
final class CameraPanelLight17 {
  private final HostRuntime runtime;
  private WeakReference<Activity> activity = new WeakReference<>(null);
  private boolean resumed;
  private boolean torchEnabled;
  private long lastCommandId;
  private Method changeTorch;
  private final Runnable heartbeat = this::publishAndSchedule;

  CameraPanelLight17(HostRuntime runtime) {
    this.runtime = runtime;
  }

  void install() throws Exception {
    Class<?> camera = Class.forName("com.sec.android.app.camera.Camera", false, runtime.loader);
    changeTorch = Reflect.method(Class.forName("p8.p", false, runtime.loader), "I", int.class);
    ModernHook.findAndHookMethod(
        camera,
        "onResume",
        new ModernHook.Callback() {
          protected void afterHookedMethod(ModernHook.Param call) {
            if (call.hasThrowable()) return;
            activity = new WeakReference<>((Activity) call.thisObject);
            resumed = true;
            publishAndSchedule();
          }
        });
    ModernHook.findAndHookMethod(
        camera,
        "onPause",
        new ModernHook.Callback() {
          protected void beforeHookedMethod(ModernHook.Param call) {
            stopTorch();
            resumed = false;
            publishAndSchedule();
          }
        });
    // Native mode changes may reset the flash. Keep an explicit Quick Panel torch
    // request until OFF or leaving Camera; saved Photo/Video flash options are untouched.
    Class<?> maker =
        Class.forName("com.samsung.android.camera.core2.maker.MakerBase", false, runtime.loader);
    ModernHook.findAndHookMethod(
        maker,
        "setPublicSetting",
        CaptureRequest.Key.class,
        Object.class,
        new ModernHook.Callback() {
          protected void beforeHookedMethod(ModernHook.Param call) {
            if (torchEnabled && CaptureRequest.FLASH_MODE.equals(call.args[0]))
              call.args[1] = CaptureRequest.FLASH_MODE_TORCH;
          }
        });
    runtime
        .context
        .getContentResolver()
        .registerContentObserver(
            CameraLightRelay.COMMAND_URI,
            false,
            new ContentObserver(runtime.worker) {
              public void onChange(boolean own) {
                receiveRequest();
              }
            });
    runtime.onChanged(
        () -> {
          if (!runtime.enabled(ConfigProvider.PANEL_LIGHT)) stopTorch();
          publishAndSchedule();
        });
  }

  private Object focusController() throws ReflectiveOperationException {
    Activity camera = activity.get();
    if (camera == null || camera.isFinishing()) return null;
    Object engine = Reflect.get(camera, "n");
    return engine == null ? null : Reflect.get(engine, "g");
  }

  private boolean canControlTorch() throws ReflectiveOperationException {
    if (!resumed || !runtime.enabled(ConfigProvider.PANEL_LIGHT)) return false;
    Activity camera = activity.get();
    if (camera == null || camera.isFinishing()) return false;
    Object settings = Reflect.get(camera, "j");
    if ((int) Reflect.call(settings, "f") != 0) return false;
    Class<?> key = Class.forName("l9.f0", false, runtime.loader);
    int mode =
        (int)
            Reflect.method(settings.getClass(), "x", key)
                .invoke(settings, Reflect.value(key, "MODE_CUSTOM_SETTING"));
    Object engine = Reflect.get(camera, "n");
    if ((mode != 0 && mode != 1)
        || engine == null
        || !"PREVIEWING".equals(String.valueOf(Reflect.get(engine, "B")))) return false;
    return runtime.context.getSystemService(PowerManager.class).getCurrentThermalStatus()
        < PowerManager.THERMAL_STATUS_SEVERE;
  }

  private void receiveRequest() {
    try {
      Bundle request =
          runtime.context.getContentResolver().call(ConfigProvider.URI, "light_read", null, null);
      if (request == null) return;
      runtime.main.post(
          () -> {
            long commandId = request.getLong("command_id");
            if (commandId == lastCommandId) return;
            lastCommandId = commandId;
            try {
              if (SystemClock.elapsedRealtime() - request.getLong("commanded_at") > 1500
                  || !canControlTorch()) return;
              Object controller = focusController();
              if (controller == null) return;
              runtime.guard.arm();
              boolean requestedEnabled = request.getBoolean("requested_enabled");
              torchEnabled = requestedEnabled;
              changeTorch.invoke(controller, requestedEnabled ? 2 : 0);
              runtime.report("QUICK PANEL: camera torch " + (requestedEnabled ? "on" : "off"));
            } catch (Throwable error) {
              torchEnabled = false;
              runtime.fail(error);
            }
            publishAndSchedule();
          });
    } catch (RuntimeException error) {
      runtime.report("QUICK PANEL: request unavailable");
    }
  }

  private void stopTorch() {
    if (!torchEnabled) return;
    torchEnabled = false;
    try {
      Object controller = focusController();
      if (controller != null) changeTorch.invoke(controller, 0);
    } catch (ReflectiveOperationException error) {
      ModernHook.log("Camera torch already disconnected");
    }
  }

  private void publishAndSchedule() {
    runtime.main.removeCallbacks(heartbeat);
    boolean available = false;
    try {
      available = canControlTorch();
    } catch (ReflectiveOperationException ignored) {
    }
    if (!available) stopTorch();
    Bundle state = new Bundle();
    state.putBoolean("available", available);
    state.putBoolean("enabled", available && torchEnabled);
    runtime.worker.post(
        () -> {
          try {
            runtime
                .context
                .getContentResolver()
                .call(ConfigProvider.URI, "light_state", null, state);
          } catch (RuntimeException ignored) {
          }
        });
    if (resumed && runtime.enabled(ConfigProvider.PANEL_LIGHT))
      runtime.main.postDelayed(heartbeat, 1000);
  }
}
