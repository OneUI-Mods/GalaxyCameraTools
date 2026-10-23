package dev.ryuya.galaxycamera;

import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Size;
import java.util.Map;

/** Changes the producer buffer before the stock preview surface and session are configured. */
final class PhotoPreviewStream17 {
  private final HostRuntime runtime;
  private final CameraHooks17 camera;
  private final Map<Size, Size> selectedSizes = new java.util.concurrent.ConcurrentHashMap<>();

  PhotoPreviewStream17(HostRuntime runtime, CameraHooks17 camera) {
    this.runtime = runtime;
    this.camera = camera;
  }

  void install() throws Exception {
    Class<?> surfaceManager = Class.forName("vb.g", false, runtime.loader);
    Class<?> resolution = Class.forName("l9.a2", false, runtime.loader);
    ModernHook.deoptimize(Reflect.method(surfaceManager, "a", int.class, int.class));
    ModernHook.deoptimize(
        Reflect.method(
            Class.forName("vb.c", false, runtime.loader),
            "k",
            android.graphics.Rect.class,
            resolution));
    // Photo's deferred-surface profile otherwise replaces the chosen dimensions with 1920x1440.
    ModernHook.findAndHookMethod(
        Class.forName("gd.i", false, runtime.loader),
        "p",
        new ModernHook.Callback() {
          protected void afterHookedMethod(ModernHook.Param call) {
            if (call.hasThrowable()
                || call.getResult() == null
                || !runtime.enabled(ConfigProvider.LIGHT_PREVIEW)
                || camera.mode() != 0
                || !camera.rearCamera()) return;
            try {
              Object original = call.getResult();
              Size size =
                  new Size((int) Reflect.call(original, "m"), (int) Reflect.call(original, "d"));
              Size selected = selectedSizes.get(size);
              if (selected == null) {
                selected = choose(size);
                selectedSizes.put(size, selected);
              }
              if (!selected.equals(size)) {
                runtime.guard.arm();
                call.setResult(
                    Reflect.value(
                        resolution,
                        "RESOLUTION_" + selected.getWidth() + "X" + selected.getHeight()));
              }
            } catch (Exception unsupported) {
              ModernHook.log("Photo preview profile: keeping native size");
            }
          }
        });
    ModernHook.findAndHookMethod(
        surfaceManager,
        "a",
        int.class,
        int.class,
        new ModernHook.Callback() {
          protected void beforeHookedMethod(ModernHook.Param call) {
            if (!runtime.enabled(ConfigProvider.LIGHT_PREVIEW) || camera.mode() != 0) return;
            try {
              Object settings = Reflect.get(Reflect.get(call.thisObject, "a"), "j");
              if ((int) Reflect.call(settings, "f") != 0) return;
              Size requested = new Size((int) call.args[0], (int) call.args[1]);
              Size selected = selectedSizes.get(requested);
              if (selected == null) {
                selected = choose(requested);
                selectedSizes.put(requested, selected);
              }
              if (selected.equals(requested)) return;
              runtime.guard.arm();
              call.args[0] = selected.getWidth();
              call.args[1] = selected.getHeight();
            } catch (Exception unsupported) {
              ModernHook.log("Photo preview stream: keeping native size");
            }
          }

          protected void afterHookedMethod(ModernHook.Param call) throws Exception {
            if (!call.hasThrowable()
                && runtime.enabled(ConfigProvider.LIGHT_PREVIEW)
                && camera.mode() == 0)
              ModernHook.log("Photo preview configured: " + Reflect.get(call.thisObject, "k"));
          }
        });
  }

  private Size choose(Size requested) throws Exception {
    CameraCharacteristics characteristics =
        runtime.context.getSystemService(CameraManager.class).getCameraCharacteristics("20");
    StreamConfigurationMap outputs =
        characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
    if (outputs == null) return requested;
    Size[] sizes = outputs.getOutputSizes(SurfaceTexture.class);
    if (sizes == null) return requested;
    Size best = requested;
    long bestArea = 0;
    for (Size size : sizes) {
      long area = (long) size.getWidth() * size.getHeight();
      if (size.getWidth() > 1440
          || size.getHeight() > 1440
          || area > 1440L * 1080
          || area < 960L * 720
          || area >= (long) requested.getWidth() * requested.getHeight()
          || (long) size.getWidth() * requested.getHeight()
              != (long) requested.getWidth() * size.getHeight()) continue;
      long duration = outputs.getOutputMinFrameDuration(SurfaceTexture.class, size);
      if (duration <= 0 || duration > 16_666_667L) continue;
      if (area > bestArea) {
        best = size;
        bestArea = area;
      }
    }
    return best;
  }
}
