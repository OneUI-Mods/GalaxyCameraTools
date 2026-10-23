package dev.ryuya.galaxycamera;

import android.database.ContentObserver;
import android.os.Bundle;
import java.lang.ref.WeakReference;

/** Keeps Samsung's tile and safety checks, routing only the camera-owned torch request. */
final class QuickPanelLight17 {
  private final HostRuntime runtime;
  private volatile boolean cameraAvailable;
  private volatile boolean cameraTorchEnabled;
  private WeakReference<Object> controller = new WeakReference<>(null);
  private final ThreadLocal<Boolean> tileClick = new ThreadLocal<>();
  private final Runnable refreshLease = this::readCameraState;

  QuickPanelLight17(HostRuntime runtime) {
    this.runtime = runtime;
  }

  void install() throws Exception {
    ClassLoader loader = runtime.loader;
    Class<?> flashlight =
        Class.forName(
            "com.android.systemui.statusbar.policy.FlashlightControllerImpl", false, loader);
    Class<?> tile = Class.forName("com.android.systemui.qs.tiles.FlashlightTile", false, loader);
    Class<?> expandable = Class.forName("com.android.systemui.animation.Expandable", false, loader);
    ModernHook.hookAllConstructors(
        flashlight,
        new ModernHook.Callback() {
          protected void afterHookedMethod(ModernHook.Param call) {
            if (!call.hasThrowable()) controller = new WeakReference<>(call.thisObject);
          }
        });
    ModernHook.findAndHookMethod(
        flashlight,
        "isAvailable",
        new ModernHook.Callback() {
          protected void afterHookedMethod(ModernHook.Param call) {
            if (call.hasThrowable() || !relayEnabled()) return;
            try {
              Object samsung = Reflect.get(call.thisObject, "mSecFlashlightController");
              if (samsung != null
                  && ((boolean) Reflect.call(samsung, "isThermalRestricted")
                      || (boolean) Reflect.call(samsung, "isFlashlightBlinking"))) return;
              call.setResult(true);
            } catch (Throwable error) {
              runtime.fail(error);
            }
          }
        });
    ModernHook.findAndHookMethod(
        flashlight,
        "isEnabled",
        new ModernHook.Callback() {
          protected void afterHookedMethod(ModernHook.Param call) {
            if (!call.hasThrowable() && relayEnabled()) call.setResult(cameraTorchEnabled);
          }
        });
    ModernHook.aroundMethod(
        Reflect.method(tile, "handleClick", expandable),
        chain -> {
          tileClick.set(true);
          try {
            return chain.proceed();
          } finally {
            tileClick.remove();
          }
        });
    ModernHook.findAndHookMethod(
        flashlight,
        "setFlashlight",
        boolean.class,
        new ModernHook.Callback() {
          protected void beforeHookedMethod(ModernHook.Param call) {
            boolean enable = (boolean) call.args[0];
            if (!relayEnabled() || (enable && !Boolean.TRUE.equals(tileClick.get()))) return;
            try {
              Object samsung = Reflect.get(call.thisObject, "mSecFlashlightController");
              if (enable
                  && samsung != null
                  && (boolean) Reflect.call(samsung, "isThermalRestricted")) return;
              runtime.guard.arm();
              call.setResult(null);
              runtime.worker.post(() -> sendRequest(enable));
            } catch (Throwable error) {
              runtime.fail(error);
            }
          }
        });
    Class<?> tileState =
        Class.forName("com.android.systemui.plugins.qs.QSTile$State", false, loader);
    ModernHook.findAndHookMethod(
        tile,
        "handleUpdateState",
        tileState,
        Object.class,
        new ModernHook.Callback() {
          protected void beforeHookedMethod(ModernHook.Param call) {
            // Reopening the panel delivers Samsung's cached CameraManager state.
            // Present the camera owner's acknowledgement instead of that stale OFF.
            if (relayEnabled()) call.args[1] = cameraTorchEnabled;
          }
        });
    // Brightness/blink controls own separate CameraManager requests and must not
    // contend with Camera. The normal tile click and native controls return on exit.
    ModernHook.findAndHookMethod(
        tile,
        "isDetailAvailable",
        new ModernHook.Callback() {
          protected void afterHookedMethod(ModernHook.Param call) {
            if (!call.hasThrowable() && relayEnabled()) call.setResult(false);
          }
        });
    runtime
        .context
        .getContentResolver()
        .registerContentObserver(
            CameraLightRelay.STATE_URI,
            false,
            new ContentObserver(runtime.worker) {
              public void onChange(boolean own) {
                readCameraState();
              }
            });
    runtime.onChanged(() -> runtime.worker.post(this::readCameraState));
    runtime.worker.post(this::readCameraState);
    runtime.ready();
  }

  private boolean relayEnabled() {
    return cameraAvailable && runtime.enabled(ConfigProvider.PANEL_LIGHT);
  }

  private void sendRequest(boolean enable) {
    try {
      Bundle input = new Bundle();
      input.putBoolean("enabled", enable);
      Bundle result =
          runtime
              .context
              .getContentResolver()
              .call(ConfigProvider.URI, "light_request", null, input);
      if (result == null || !result.getBoolean("accepted")) readCameraState();
    } catch (RuntimeException error) {
      cameraAvailable = false;
      notifyTile();
    }
  }

  private void readCameraState() {
    runtime.worker.removeCallbacks(refreshLease);
    boolean previousAvailable = cameraAvailable;
    boolean previousEnabled = cameraTorchEnabled;
    try {
      Bundle state =
          runtime.context.getContentResolver().call(ConfigProvider.URI, "light_read", null, null);
      cameraAvailable = state != null && state.getBoolean("available");
      cameraTorchEnabled = cameraAvailable && state.getBoolean("enabled");
    } catch (RuntimeException error) {
      cameraAvailable = false;
      cameraTorchEnabled = false;
    }
    if (previousAvailable != cameraAvailable || previousEnabled != cameraTorchEnabled) notifyTile();
    if (cameraAvailable) runtime.worker.postDelayed(refreshLease, 1000);
  }

  private void notifyTile() {
    runtime.main.post(
        () -> {
          Object current = controller.get();
          if (current == null) return;
          try {
            boolean available = (boolean) Reflect.call(current, "isAvailable");
            boolean enabled = (boolean) Reflect.call(current, "isEnabled");
            var dispatch =
                Reflect.method(current.getClass(), "dispatchListeners", int.class, boolean.class);
            dispatch.invoke(current, 2, available);
            dispatch.invoke(current, 1, enabled);
          } catch (Throwable error) {
            runtime.fail(error);
          }
        });
  }
}
