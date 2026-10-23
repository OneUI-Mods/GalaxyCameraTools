package dev.ryuya.galaxycamera;

import android.os.Bundle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;

/** Shares only numeric, runtime-read stock curves; no Samsung code or images are copied. */
final class StockZoomCurves {
  static String encode(Map<?, ?> nativeCurves) throws ReflectiveOperationException, JSONException {
    JSONArray output = new JSONArray();
    for (Object value : nativeCurves.values()) {
      if (!(value instanceof List<?> points)) continue;
      JSONArray curve = new JSONArray();
      for (Object point : points) {
        curve.put(
            new JSONArray()
                .put((float) Reflect.get(point, "a"))
                .put((int) Reflect.get(point, "b")));
      }
      output.put(curve);
    }
    return output.toString();
  }

  static List<List<ZoomCurve.Point>> decode(String encoded) throws JSONException {
    if (encoded == null || encoded.length() > 40_000)
      throw new JSONException("Curve data too large");
    JSONArray input = new JSONArray(encoded);
    if (input.length() > 16) throw new JSONException("Too many curves");
    List<List<ZoomCurve.Point>> curves = new ArrayList<>();
    for (int index = 0; index < input.length(); index++) {
      JSONArray items = input.getJSONArray(index);
      if (items.length() > 1000) throw new JSONException("Too many points");
      List<ZoomCurve.Point> points = new ArrayList<>();
      for (int item = 0; item < items.length(); item++) {
        JSONArray point = items.getJSONArray(item);
        points.add(new ZoomCurve.Point((float) point.getDouble(0), point.getInt(1)));
      }
      int duration = ZoomCurve.duration(points);
      if (duration <= 0 || duration > 3000) throw new JSONException("Invalid curve");
      curves.add(points);
    }
    return curves;
  }

  static List<ZoomCurve.Point> read(HostRuntime runtime, int fromCamera, int toCamera) {
    try {
      float from = lensZoom(fromCamera), to = lensZoom(toCamera);
      if (from <= 0 || to <= 0 || from == to) return null;
      Bundle data =
          runtime
              .context
              .getContentResolver()
              .call(ConfigProvider.URI, "zoom_curves_read", null, null);
      if (data == null) return null;
      return ZoomCurve.transition(decode(data.getString("curves", "[]")), from, to);
    } catch (RuntimeException | JSONException error) {
      ModernHook.log("Stock zoom curve unavailable; using stock easing fallback");
    }
    return null;
  }

  private static float lensZoom(int cameraId) {
    return switch (cameraId) {
      case 0, 102 -> 1f;
      case 100 -> 3f;
      case 101 -> .6f;
      case 104 -> 5f;
      default -> -1f;
    };
  }
}
