package dev.ryuya.galaxycamera;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Camera 17's curve runner uses delayed zoom steps, independently of sensor FPS. */
final class PhotoZoomTiming17 {
  private final HostRuntime runtime;
  private final CameraHooks17 camera;

  PhotoZoomTiming17(HostRuntime runtime, CameraHooks17 camera) {
    this.runtime = runtime;
    this.camera = camera;
  }

  void install() throws Exception {
    Class<?> zoomView =
        Class.forName(
            "com.sec.android.app.camera.layer.keyscreen.zoom.ZoomView", false, runtime.loader);
    Class<?> pointType = Class.forName("l8.w", false, runtime.loader);
    Constructor<?> pointConstructor = pointType.getDeclaredConstructor(float.class, int.class);
    pointConstructor.setAccessible(true);
    ModernHook.aroundMethod(
        Reflect.method(zoomView, "H2", int.class, int.class),
        chain -> {
          if (!runtime.enabled(ConfigProvider.FPS) || camera.mode() != 0) return chain.proceed();
          Object curve = Reflect.get(chain.getThisObject(), "k");
          if (curve == null) return chain.proceed();
          Object original = Reflect.get(curve, "a");
          if (!(original instanceof Map<?, ?> nativeCurves)) return chain.proceed();
          Map<Object, Object> sampledCurves = new LinkedHashMap<>();
          int subdivisions = 0;
          for (Map.Entry<?, ?> entry : nativeCurves.entrySet()) {
            if (!(entry.getValue() instanceof List<?> points)) return chain.proceed();
            List<ZoomCurve.Point> coordinates = new ArrayList<>();
            for (Object point : points) {
              coordinates.add(
                  new ZoomCurve.Point(
                      (float) Reflect.get(point, "a"), (int) Reflect.get(point, "b")));
            }
            List<ZoomCurve.Point> sampled = ZoomCurve.atSixtyHz(coordinates);
            ArrayList<Object> replacement = new ArrayList<>();
            for (ZoomCurve.Point point : sampled) {
              replacement.add(pointConstructor.newInstance(point.zoom(), point.delayMillis()));
            }
            subdivisions += sampled.size() - coordinates.size();
            sampledCurves.put(entry.getKey(), replacement);
          }
          runtime.guard.arm();
          camera.beginZoomMeasurement();
          // Only this synchronous selection reads the replacement map. Its queued runner owns
          // a separate list; subsequent modes and OFF continue to see the untouched native map.
          Reflect.field(curve.getClass(), "a").set(curve, sampledCurves);
          try {
            ModernHook.log("Photo zoom: native curve subdivided; additional steps=" + subdivisions);
            return chain.proceed();
          } finally {
            Reflect.field(curve.getClass(), "a").set(curve, original);
          }
        });
  }
}
