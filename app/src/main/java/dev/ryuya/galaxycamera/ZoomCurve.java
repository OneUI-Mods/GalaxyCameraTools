package dev.ryuya.galaxycamera;

import java.util.List;

/** Time-based sampling preserves native easing, endpoints and duration even after missed frames. */
final class ZoomCurve {
  record Point(float zoom, int delayMillis) {}

  static int duration(List<Point> points) {
    int total = 0;
    if (points.size() < 2 || points.size() > 1000) return -1;
    for (int index = 0; index < points.size(); index++) {
      Point point = points.get(index);
      if (!Float.isFinite(point.zoom())
          || point.zoom() <= 0
          || point.delayMillis() < 0
          || point.delayMillis() > 1000
          || (index < points.size() - 1 && point.delayMillis() == 0)) return -1;
      total += point.delayMillis();
    }
    return points.get(points.size() - 1).delayMillis() == 0 ? total : -1;
  }

  static float valueAt(List<Point> points, int elapsedMillis) {
    int elapsed = Math.max(0, elapsedMillis);
    for (int index = 0; index < points.size() - 1; index++) {
      Point point = points.get(index);
      if (elapsed < point.delayMillis()) {
        return point.zoom()
            + (points.get(index + 1).zoom() - point.zoom()) * elapsed / point.delayMillis();
      }
      elapsed -= point.delayMillis();
    }
    return points.get(points.size() - 1).zoom();
  }

  static int nextIndex(List<Point> points, int elapsedMillis) {
    int time = 0;
    for (int index = 0; index < points.size() - 1; index++) {
      time += points.get(index).delayMillis();
      if (time >= elapsedMillis) return index + 1;
    }
    return points.size() - 1;
  }
}
