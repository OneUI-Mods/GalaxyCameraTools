package dev.ryuya.galaxycamera;

import android.app.Activity;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.*;
import android.util.*;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.util.*;

/** Samsung Camera 17.0.00.43 / BZI9 only. Never shared with an Android 16 contract. */
final class CameraHooks17 {
  private final HostRuntime rt;
  private volatile WeakReference<Activity> activity = new WeakReference<>(null);
  private final ThreadLocal<Object> owner = new ThreadLocal<>();
  private final Map<String, CameraCharacteristics> capabilities =
      new java.util.concurrent.ConcurrentHashMap<>();
  private Class<?> settingsKey;
  private Object modeKey;
  private String stream = "";
  private final Map<String, Size> eligiblePreviewSizes =
      new java.util.concurrent.ConcurrentHashMap<>();
  private Range<Integer> zoomResultRange;
  private Long zoomExposure;
  private long firstTimestamp, lastTimestamp, firstFrame;
  private int samples;
  private long zoomUntil, zoomLastTimestamp, zoomIntervals, zoomTotalNanos, zoomSlowIntervals;
  private Range<Integer> zoomRequest;

  CameraHooks17(HostRuntime runtime) {
    rt = runtime;
  }

  void install() throws Exception {
    ClassLoader l = rt.loader;
    Class<?> camera = Class.forName("com.sec.android.app.camera.Camera", false, l);
    settingsKey = Class.forName("l9.f0", false, l);
    modeKey = Reflect.value(settingsKey, "MODE_CUSTOM_SETTING");
    Reflect.field(camera, "j");
    ModernHook.findAndHookMethod(
        camera,
        "onCreate",
        Bundle.class,
        new ModernHook.Callback() {
          protected void beforeHookedMethod(ModernHook.Param p) {
            activity = new WeakReference<>((Activity) p.thisObject);
          }
        });
    Class<?> maker = Class.forName("com.samsung.android.camera.core2.maker.MakerBase", false, l);
    for (Method method :
        List.of(
            Reflect.method(maker, "setPublicSetting", CaptureRequest.Key.class, Object.class),
            Reflect.method(maker, "setSessionKeys", Map.class))) {
      ModernHook.aroundMethod(
          method,
          chain -> {
            Object previous = owner.get();
            owner.set(chain.getThisObject());
            try {
              return chain.proceed();
            } finally {
              if (previous == null) owner.remove();
              else owner.set(previous);
            }
          });
    }
    Class<?> requests =
        Class.forName(
            "com.samsung.android.camera.core2.local.vendorkey.SemCaptureRequest", false, l);
    ModernHook.findAndHookMethod(
        requests,
        "e",
        Map.class,
        String.class,
        CaptureRequest.Key.class,
        Object.class,
        new ModernHook.Callback() {
          protected void beforeHookedMethod(ModernHook.Param p) {
            if (!rt.enabled(ConfigProvider.FPS) || owner.get() == null) return;
            try {
              if (CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE.equals(p.args[2])) {
                previewRequest(p, owner.get());
              } else if (p.args[2] instanceof CaptureRequest.Key<?> key
                  && key.getName().equals("samsung.android.control.transientAction")) {
                previewZoomAction(p, owner.get());
              }
            } catch (Throwable error) {
              rt.fail(error);
            }
          }
        });
    Class<?> callback =
        Class.forName("com.samsung.android.camera.core2.maker.PhotoMakerBase$6", false, l);
    Class<?> cap = Class.forName("com.samsung.android.camera.core2.CamCapability", false, l);
    ModernHook.findAndHookMethod(
        callback,
        "onPreviewCaptureResult",
        TotalCaptureResult.class,
        cap,
        new ModernHook.Callback() {
          protected void afterHookedMethod(ModernHook.Param p) {
            if (!p.hasThrowable() && rt.guard.allowed()) measure((TotalCaptureResult) p.args[0]);
          }
        });
    Class<?> sessionConfig =
        Class.forName("com.samsung.android.camera.core2.container.SessionConfig", false, l);
    Class<?> sessionMode =
        Class.forName("com.samsung.android.camera.core2.CamDevice$SessionMode", false, l);
    Class<?> device =
        Class.forName("com.samsung.android.camera.core2.device.CamDeviceImpl", false, l);
    ModernHook.findAndHookMethod(
        device,
        "X",
        sessionConfig,
        sessionMode,
        new ModernHook.Callback() {
          protected void beforeHookedMethod(ModernHook.Param p) {
            if (!rt.enabled(ConfigProvider.FPS) || mode() != 0) return;
            try {
              String id = (String) Reflect.get(p.thisObject, "e");
              if (!eligiblePreviewSizes.containsKey(id)) {
                Object surfaces = Reflect.get(p.args[0], "a");
                Object preview = Reflect.get(surfaces, "a");
                if (preview == null) return;
                Size size = (Size) Reflect.get(preview, "c");
                if (!supportsPreview60(id, size)) return;
                eligiblePreviewSizes.put(id, size);
              }
              Object config = Reflect.get(p.args[0], "k");
              Map<?, ?> previewBuilders = (Map<?, ?>) Reflect.get(config, "a");
              for (Object value : previewBuilders.values()) {
                if (value instanceof CaptureRequest.Builder builder) {
                  Range<Integer> previous = builder.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE);
                  builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(60, 60));
                  ModernHook.log(
                      "Photo session: id=" + id + "; initial FPS=" + previous + " -> [60, 60]");
                }
              }
            } catch (ReflectiveOperationException
                | CameraAccessException
                | IllegalArgumentException error) {
              rt.fail(error);
            }
          }
        });
    new PhotoZoomTiming17(rt, this).install();
    new VideoModes17(rt, this).install();
    new UltraWideLight17(rt).install();
    new FlashFocus17(rt, this).install();
    new CameraPanelLight17(rt).install();
    rt.ready();
  }

  int mode() {
    Activity a = activity.get();
    if (a == null || a.isFinishing()) return Integer.MIN_VALUE;
    try {
      Object settings = Reflect.get(a, "j");
      return (int) Reflect.method(settings.getClass(), "x", settingsKey).invoke(settings, modeKey);
    } catch (ReflectiveOperationException e) {
      return Integer.MIN_VALUE;
    }
  }

  int backVideoResolution() throws ReflectiveOperationException {
    Activity a = activity.get();
    if (a == null) return Integer.MIN_VALUE;
    Object settings = Reflect.get(a, "j");
    return (int)
        Reflect.method(settings.getClass(), "d", settingsKey)
            .invoke(settings, Reflect.value(settingsKey, "BACK_CAMCORDER_RESOLUTION"));
  }

  boolean busy() {
    try {
      Activity a = activity.get();
      Object engine = a == null ? null : Reflect.get(a, "n");
      return engine != null && !"IDLE".equals(String.valueOf(Reflect.get(engine, "C")));
    } catch (ReflectiveOperationException ignored) {
      return true;
    }
  }

  void restoreNormalVideoSelection(Class<?> resolution) throws ReflectiveOperationException {
    if (!rt.available() || rt.configured(ConfigProvider.VIDEO) || !rt.guard.allowed()) return;
    Activity a = activity.get();
    if (a == null) return;
    Object engine = Reflect.get(a, "n");
    if (engine == null || !"IDLE".equals(String.valueOf(Reflect.get(engine, "C")))) return;
    Object selected =
        Reflect.method(resolution, "j", int.class).invoke(null, backVideoResolution());
    String name = selected.toString();
    if (!Set.of("RESOLUTION_1920X1080_120FPS", "RESOLUTION_3840X2160_120FPS").contains(name))
      return;
    Object fallback = Reflect.value(resolution, name.replace("_120FPS", ""));
    Object settings = Reflect.get(a, "j");
    rt.guard.arm();
    Reflect.method(settings.getClass(), "V", int.class, settingsKey)
        .invoke(
            settings,
            Reflect.call(fallback, "e"),
            Reflect.value(settingsKey, "BACK_CAMCORDER_RESOLUTION"));
    rt.report("VIDEO: disabled; restored native 30 fps selection");
  }

  private boolean supportsPreview60(String id, Size size) throws CameraAccessException {
    if (size == null) return false;
    CameraCharacteristics characteristics =
        rt.context.getSystemService(CameraManager.class).getCameraCharacteristics(id);
    Range<Integer>[] ranges =
        characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
    StreamConfigurationMap map =
        characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
    if (ranges == null || map == null || !Arrays.asList(ranges).contains(new Range<>(60, 60)))
      return false;
    try {
      return PreviewPolicy.eligible(
          true,
          mode(),
          1,
          true,
          map.getOutputMinFrameDuration(SurfaceTexture.class, size),
          rt.context.getSystemService(PowerManager.class).getCurrentThermalStatus());
    } catch (IllegalArgumentException unsupported) {
      return false;
    }
  }

  private void previewZoomAction(ModernHook.Param p, Object maker) throws Exception {
    if (mode() != 0
        || (int) Reflect.call(maker, "getMakerType") != 1
        || p.args[0] != Reflect.get(maker, "mPreviewRequestBuilderMap")
        || !(p.args[3] instanceof Integer action)
        || (action & 1) == 0) return;
    String id = (String) p.args[1];
    Size size = (Size) Reflect.get(maker, "mMainPreviewSurfaceSize");
    if (!supportsPreview60(id, size)) return;
    // Only the preview's transient-zoom bit is changed. Still capture and other action bits
    // keep Samsung's original values, as do unsupported streams and thermal protection.
    rt.guard.arm();
    p.args[3] = action & ~1;
  }

  private void previewRequest(ModernHook.Param p, Object maker) throws Exception {
    if (p.args[0] != Reflect.get(maker, "mPreviewRequestBuilderMap")) return;
    int type = (int) Reflect.call(maker, "getMakerType");
    int shooting = (int) Reflect.call(maker, "getMakerShootingMode");
    if (mode() != 0 || type != 1 || (shooting != 1 && shooting != 2)) return;
    String id = (String) p.args[1];
    CameraCharacteristics c = capabilities.get(id);
    if (c == null) {
      c = rt.context.getSystemService(CameraManager.class).getCameraCharacteristics(id);
      capabilities.put(id, c);
    }
    Range<Integer>[] ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
    boolean fixed = ranges != null && Arrays.asList(ranges).contains(new Range<>(60, 60));
    Size size = (Size) Reflect.get(maker, "mMainPreviewSurfaceSize");
    StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
    long duration = 0;
    if (map != null && size != null) {
      try {
        duration = map.getOutputMinFrameDuration(SurfaceTexture.class, size);
      } catch (IllegalArgumentException ignored) {
      }
    }
    int thermal = rt.context.getSystemService(PowerManager.class).getCurrentThermalStatus();
    if (!PreviewPolicy.eligible(true, mode(), type, fixed, duration, thermal)) {
      rt.report(
          "PHOTO: native fps; id="
              + id
              + "; preview="
              + size
              + "; minDuration="
              + duration
              + "; fixed60="
              + fixed
              + "; thermal="
              + thermal);
      return;
    }
    eligiblePreviewSizes.put(id, size);
    rt.guard.arm();
    p.args[3] = new Range<>(60, 60);
    stream = "id=" + id + "; preview=" + size;
    rt.report("PHOTO: requested 60 fps; " + stream);
  }

  synchronized void beginZoomMeasurement() {
    zoomUntil = SystemClock.uptimeMillis() + 1500;
    zoomLastTimestamp = zoomIntervals = zoomTotalNanos = zoomSlowIntervals = 0;
    zoomRequest = null;
    zoomResultRange = null;
    zoomExposure = null;
  }

  private synchronized void measureZoom(TotalCaptureResult result, long timestamp) {
    if (zoomUntil == 0) return;
    if (SystemClock.uptimeMillis() >= zoomUntil) {
      if (zoomIntervals > 0)
        ModernHook.log(
            String.format(
                Locale.ROOT,
                "Photo zoom preview: %.1f fps; intervals=%d; over25ms=%d; requested=%s; result=%s;"
                    + " exposure=%s",
                zoomIntervals * 1_000_000_000d / zoomTotalNanos,
                zoomIntervals,
                zoomSlowIntervals,
                zoomRequest,
                zoomResultRange,
                zoomExposure));
      zoomUntil = 0;
      return;
    }
    long interval = timestamp - zoomLastTimestamp;
    if (zoomLastTimestamp > 0 && interval > 0 && interval < 100_000_000L) {
      zoomIntervals++;
      zoomTotalNanos += interval;
      if (interval > 25_000_000L) zoomSlowIntervals++;
    }
    zoomLastTimestamp = timestamp;
    zoomRequest = result.getRequest().get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE);
    zoomResultRange = result.get(CaptureResult.CONTROL_AE_TARGET_FPS_RANGE);
    zoomExposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
  }

  private void measure(TotalCaptureResult result) {
    if (mode() != 0) return;
    Long timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
    if (timestamp == null) return;
    measureZoom(result, timestamp);
    if (firstTimestamp == 0 || timestamp <= lastTimestamp) {
      firstTimestamp = timestamp;
      firstFrame = result.getFrameNumber();
      samples = 0;
    }
    lastTimestamp = timestamp;
    samples++;
    long elapsed = timestamp - firstTimestamp;
    if (elapsed >= 2_000_000_000L && samples > 1) {
      double fps = (result.getFrameNumber() - firstFrame) * 1_000_000_000d / elapsed;
      double callbacks = (samples - 1) * 1_000_000_000d / elapsed;
      Long duration = result.get(CaptureResult.SENSOR_FRAME_DURATION);
      Range<Integer> requested =
          result.getRequest().get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE);
      rt.report(
          String.format(
              Locale.ROOT,
              "PHOTO: %.1f fps; callbacks=%.1f; frameDuration=%s; request=%s; override=%s; %s",
              fps,
              callbacks,
              duration,
              requested,
              rt.enabled(ConfigProvider.FPS),
              stream));
      firstTimestamp = timestamp;
      firstFrame = result.getFrameNumber();
      samples = 1;
    }
  }
}
