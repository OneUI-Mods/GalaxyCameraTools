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
  private static final Range<Integer> PREVIEW_60 = new Range<>(60, 60);
  private static final CaptureRequest.Key<Float> ZOOM_JUMP_TARGET =
      new CaptureRequest.Key<>("samsung.android.control.zoomJumpTargetRatio", Float.class);
  private final Map<String, Map<Size, Boolean>> previewSupport =
      new java.util.concurrent.ConcurrentHashMap<>();
  private volatile int thermalStatus;
  private long nextStatisticsRead;
  private final Map<String, CameraCharacteristics> capabilities =
      new java.util.concurrent.ConcurrentHashMap<>();
  private Class<?> settingsKey;
  private Field cameraSettings;
  private Method readMode;
  private Object modeKey;
  private String stream = "";
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
    cameraSettings = Reflect.field(camera, "j");
    readMode = Reflect.method(Class.forName("vc.j", false, l), "x", settingsKey);
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
    PowerManager power = rt.context.getSystemService(PowerManager.class);
    thermalStatus = power.getCurrentThermalStatus();
    power.addThermalStatusListener(rt.context.getMainExecutor(), status -> thermalStatus = status);
    for (Method method :
        List.of(
            Reflect.method(maker, "setPublicSetting", CaptureRequest.Key.class, Object.class),
            Reflect.method(maker, "setSessionKeys", Map.class))) {
      ModernHook.hookMethod(
          method,
          new ModernHook.Callback() {
            protected void afterHookedMethod(ModernHook.Param call) {
              if (call.hasThrowable()) return;
              boolean fpsChange =
                  call.args.length == 2
                      ? CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE.equals(call.args[0])
                      : ((Map<?, ?>) call.args[0])
                          .containsKey(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE);
              if (fpsChange) applyPreviewFps(call.thisObject);
            }
          });
    }
    // Patch the actual preview builders, including paths whose vendor helpers were inlined.
    // No global Builder.set hook and no changes to the picture/record request maps.
    ModernHook.findAndHookMethod(
        maker,
        "applySettings",
        new ModernHook.Callback() {
          protected void beforeHookedMethod(ModernHook.Param call) {
            applyPreviewFps(call.thisObject);
          }
        });
    Class<?> photoMaker =
        Class.forName("com.samsung.android.camera.core2.maker.PhotoMakerBase", false, l);
    ModernHook.findAndHookMethod(
        photoMaker,
        "startPreviewRepeating",
        new ModernHook.Callback() {
          protected void beforeHookedMethod(ModernHook.Param call) {
            applyPreviewFps(call.thisObject);
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
      return (int) readMode.invoke(cameraSettings.get(a), modeKey);
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

  private void applyPreviewFps(Object maker) {
    if (!rt.enabled(ConfigProvider.FPS) || mode() != 0) return;
    try {
      int type = (int) Reflect.call(maker, "getMakerType");
      int shooting = (int) Reflect.call(maker, "getMakerShootingMode");
      if (type != 1 || (shooting != 1 && shooting != 2)) return;
      Object device = Reflect.get(maker, "mCamDevice");
      if (device == null) return;
      String id = (String) Reflect.call(device, "m");
      Size size = (Size) Reflect.get(maker, "mMainPreviewSurfaceSize");
      if (size == null || thermalStatus >= 3) return;
      Map<Size, Boolean> sizes =
          previewSupport.computeIfAbsent(
              id, ignored -> new java.util.concurrent.ConcurrentHashMap<>());
      Boolean supported = sizes.get(size);
      CameraCharacteristics characteristics = capabilities.get(id);
      if (characteristics == null) {
        characteristics =
            rt.context.getSystemService(CameraManager.class).getCameraCharacteristics(id);
        capabilities.put(id, characteristics);
      }
      if (supported == null) {
        Range<Integer>[] ranges =
            characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        StreamConfigurationMap outputs =
            characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        long minimum =
            outputs == null ? 0 : outputs.getOutputMinFrameDuration(SurfaceTexture.class, size);
        supported =
            PreviewPolicy.eligible(
                true,
                0,
                type,
                ranges != null && Arrays.asList(ranges).contains(PREVIEW_60),
                minimum,
                thermalStatus);
        sizes.put(size, supported);
      }
      if (!supported) return;
      List<CaptureRequest.Key<?>> physicalKeys =
          characteristics.getAvailablePhysicalCameraRequestKeys();
      Map<?, ?> builders = (Map<?, ?>) Reflect.get(maker, "mPreviewRequestBuilderMap");
      rt.guard.arm();
      for (Map.Entry<?, ?> item : builders.entrySet()) {
        if (!(item.getValue() instanceof CaptureRequest.Builder builder)) continue;
        Float jumpTarget = builder.get(ZOOM_JUMP_TARGET);
        if (jumpTarget != null && jumpTarget > 0f) builder.set(ZOOM_JUMP_TARGET, 0f);
        if (!PREVIEW_60.equals(builder.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE)))
          builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, PREVIEW_60);
        if (physicalKeys != null
            && physicalKeys.contains(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE)
            && item.getKey() instanceof Pair<?, ?> pair
            && pair.second instanceof Set<?> physicalIds) {
          for (Object physicalId : physicalIds)
            builder.setPhysicalCameraKey(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, PREVIEW_60, (String) physicalId);
        }
      }
      stream = "id=" + id + "; preview=" + size;
      rt.report("PHOTO: requested 60 fps; " + stream);
    } catch (IllegalArgumentException unsupported) {
      // A stale or unsupported stream keeps the native setup, without poisoning the host.
      ModernHook.log(
          "Photo preview: retaining native stream: " + unsupported.getClass().getSimpleName());
    } catch (ReflectiveOperationException | CameraAccessException error) {
      rt.fail(error);
    }
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
    samples++;
    long now = SystemClock.uptimeMillis();
    if (zoomUntil == 0 && now < nextStatisticsRead) return;
    nextStatisticsRead = now + 200;
    Long timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
    if (timestamp == null) return;
    measureZoom(result, timestamp);
    if (firstTimestamp == 0 || timestamp <= lastTimestamp) {
      firstTimestamp = timestamp;
      firstFrame = result.getFrameNumber();
      samples = 0;
    }
    lastTimestamp = timestamp;
    long elapsed = timestamp - firstTimestamp;
    if (elapsed >= 5_000_000_000L && samples > 1 && mode() == 0) {
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
