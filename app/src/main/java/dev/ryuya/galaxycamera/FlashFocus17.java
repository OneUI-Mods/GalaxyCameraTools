package dev.ryuya.galaxycamera;

import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.os.SystemClock;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

/** Holds the measured lens position for a flash photo, without changing saved focus settings. */
final class FlashFocus17 {
  private final HostRuntime runtime;
  private final CameraHooks17 camera;
  private volatile FocusSample sample;
  private volatile FocusHold hold;
  private Method setPublicSetting, applySettings;
  private final Runnable timeout = () -> release("timeout");

  private record FocusSample(WeakReference<Object> maker, float distance, long sampledAtMillis) {}

  private record FocusHold(
      Object maker, Object engine, Object previousAfMode, Object distance, float lockedDistance) {}

  FlashFocus17(HostRuntime runtime, CameraHooks17 hooks) {
    this.runtime = runtime;
    camera = hooks;
  }

  void install() throws Exception {
    ClassLoader loader = runtime.loader;
    Class<?> makerClass =
        Class.forName("com.samsung.android.camera.core2.maker.MakerBase", false, loader);
    Class<?> photoMakerClass =
        Class.forName("com.samsung.android.camera.core2.maker.PhotoMakerBase", false, loader);
    Class<?> engineClass = Class.forName("p8.e0", false, loader);
    setPublicSetting =
        Reflect.method(makerClass, "setPublicSetting", CaptureRequest.Key.class, Object.class);
    applySettings = Reflect.method(makerClass, "applySettings");
    ModernHook.hookMethod(
        setPublicSetting,
        new ModernHook.Callback() {
          protected void beforeHookedMethod(ModernHook.Param call) {
            FocusHold current = hold;
            if (current == null || current.maker != call.thisObject) return;
            if (CaptureRequest.CONTROL_AF_MODE.equals(call.args[0]))
              call.args[1] = CaptureRequest.CONTROL_AF_MODE_OFF;
            else if (CaptureRequest.LENS_FOCUS_DISTANCE.equals(call.args[0]))
              call.args[1] = current.lockedDistance;
          }
        });
    Class<?> previewCallbackClass =
        Class.forName("com.samsung.android.camera.core2.maker.PhotoMakerBase$6", false, loader);
    // Only metadata is retained; no images or camera content leave this process.
    ModernHook.findAndHookMethod(
        previewCallbackClass,
        "onPreviewCaptureResult",
        TotalCaptureResult.class,
        Class.forName("com.samsung.android.camera.core2.CamCapability", false, loader),
        new ModernHook.Callback() {
          protected void afterHookedMethod(ModernHook.Param call) {
            if (call.hasThrowable() || hold != null || !runtime.enabled(ConfigProvider.FOCUS))
              return;
            try {
              Float distance =
                  ((TotalCaptureResult) call.args[0]).get(CaptureResult.LENS_FOCUS_DISTANCE);
              if (distance != null && Float.isFinite(distance) && distance >= 0)
                sample =
                    new FocusSample(
                        new WeakReference<>(Reflect.get(call.thisObject, "this$0")),
                        distance,
                        SystemClock.elapsedRealtime());
            } catch (Throwable error) {
              runtime.fail(error);
            }
          }
        });
    ModernHook.findAndHookMethod(
        Class.forName("p8.p", false, loader),
        "J",
        boolean.class,
        Class.forName("q8.l", false, loader),
        new ModernHook.Callback() {
          protected void beforeHookedMethod(ModernHook.Param call) {
            if (!runtime.enabled(ConfigProvider.FOCUS)
                || !(boolean) call.args[0]
                || camera.mode() != 0
                || hold != null) return;
            try {
              FocusSample latest = sample;
              if (latest == null || SystemClock.elapsedRealtime() - latest.sampledAtMillis > 350)
                return;
              Object currentMaker = latest.maker.get();
              if (currentMaker == null) return;
              Class<?> flashType = Class.forName("l9.k2", false, loader);
              int condition = (int) Reflect.call(call.args[1], "f");
              boolean flashRequired =
                  (boolean)
                      Reflect.method(call.thisObject.getClass(), "h", flashType, int.class)
                          .invoke(
                              call.thisObject, Reflect.value(flashType, "PHOTO_FLASH"), condition);
              if (!flashRequired) return;
              Object previousAfMode =
                  Reflect.method(photoMakerClass, "getPublicSetting", CaptureRequest.Key.class)
                      .invoke(currentMaker, CaptureRequest.CONTROL_AF_MODE);
              Object distance =
                  Reflect.method(photoMakerClass, "getPublicSetting", CaptureRequest.Key.class)
                      .invoke(currentMaker, CaptureRequest.LENS_FOCUS_DISTANCE);
              if (!(previousAfMode instanceof Integer)
                  || (int) previousAfMode == CaptureRequest.CONTROL_AF_MODE_OFF) return;
              runtime.guard.arm();
              hold =
                  new FocusHold(
                      currentMaker,
                      Reflect.get(call.thisObject, "e"),
                      previousAfMode,
                      distance,
                      latest.distance);
              runtime.main.postDelayed(timeout, 8000);
              setPublicSetting.invoke(
                  currentMaker, CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
              setPublicSetting.invoke(
                  currentMaker, CaptureRequest.LENS_FOCUS_DISTANCE, latest.distance);
              applySettings.invoke(currentMaker);
              runtime.report("FLASH: focus held at " + latest.distance + " diopters");
            } catch (Throwable error) {
              release("error");
              runtime.fail(error);
            }
          }

          protected void afterHookedMethod(ModernHook.Param call) {
            if (call.hasThrowable()) release("cancelled");
          }
        });
    // Samsung's native pre-capture sequence then waits for AE only, not an AF scan
    // that cannot finish with AF disabled. Exposure metering and flash timing remain native.
    ModernHook.findAndHookMethod(
        engineClass,
        "U",
        new ModernHook.Callback() {
          protected void afterHookedMethod(ModernHook.Param call) {
            FocusHold current = hold;
            if (!call.hasThrowable() && current != null && current.engine == call.thisObject)
              call.setResult(false);
          }
        });
    ModernHook.findAndHookMethod(
        engineClass,
        "f",
        Class.forName("z8.m0", false, loader),
        new ModernHook.Callback() {
          protected void afterHookedMethod(ModernHook.Param call) {
            FocusHold current = hold;
            if (!call.hasThrowable()
                && current != null
                && current.engine == call.thisObject
                && "IDLE".equals(call.args[0].toString())) release("complete");
          }
        });
    ModernHook.findAndHookMethod(
        Class.forName("com.sec.android.app.camera.Camera", false, loader),
        "onPause",
        new ModernHook.Callback() {
          protected void beforeHookedMethod(ModernHook.Param call) {
            release("camera paused");
            sample = null;
          }
        });
    runtime.onChanged(
        () -> {
          if (!runtime.enabled(ConfigProvider.FOCUS)) release("disabled");
        });
  }

  private synchronized void release(String reason) {
    FocusHold previous = hold;
    if (previous == null) return;
    hold = null;
    sample = null;
    runtime.main.removeCallbacks(timeout);
    try {
      setPublicSetting.invoke(
          previous.maker, CaptureRequest.LENS_FOCUS_DISTANCE, previous.distance);
      setPublicSetting.invoke(
          previous.maker, CaptureRequest.CONTROL_AF_MODE, previous.previousAfMode);
      applySettings.invoke(previous.maker);
      runtime.report("FLASH: autofocus restored (" + reason + ")");
    } catch (ReflectiveOperationException error) {
      // A disconnect already released this maker; never reopen a camera to restore it.
      ModernHook.log("Flash focus restore after disconnect: " + error.getClass().getSimpleName());
    }
  }
}
