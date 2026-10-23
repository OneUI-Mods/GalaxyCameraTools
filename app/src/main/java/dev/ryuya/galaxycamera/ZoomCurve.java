package dev.ryuya.galaxycamera;

import java.util.ArrayList;
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

  static List<Point> transition(List<List<Point>> curves, float from, float to) {
    // Stock Camera checks an exact pair before taking a subsection of a wider curve.
    for (List<Point> curve : curves) {
      if (!curve.isEmpty()
          && curve.get(0).zoom() == from
          && curve.get(curve.size() - 1).zoom() == to
          && between(curve, from, to) != null) return List.copyOf(curve);
    }
    for (List<Point> curve : curves) {
      List<Point> section = between(curve, from, to);
      if (section != null) return section;
    }
    return null;
  }

  static List<Point> between(List<Point> source, float from, float to) {
    if (duration(source) <= 0 || from == to) return null;
    float first = source.get(0).zoom(), last = source.get(source.size() - 1).zoom();
    boolean increasing = last > first;
    if ((to > from) != increasing
        || from < Math.min(first, last)
        || from > Math.max(first, last)
        || to < Math.min(first, last)
        || to > Math.max(first, last)) return null;
    for (int i = 1; i < source.size(); i++) {
      if ((source.get(i).zoom() - source.get(i - 1).zoom()) * (increasing ? 1 : -1) < 0)
        return null;
    }
    int start = timeAt(source, from), end = timeAt(source, to);
    if (end <= start) return null;
    List<Point> points = new ArrayList<>();
    int absolute = 0, previousTime = start;
    float previousZoom = from;
    for (Point point : source) {
      if (absolute > start && absolute < end) {
        points.add(new Point(previousZoom, absolute - previousTime));
        previousZoom = point.zoom();
        previousTime = absolute;
      }
      absolute += point.delayMillis();
    }
    points.add(new Point(previousZoom, end - previousTime));
    points.add(new Point(to, 0));
    return points;
  }

  private static int timeAt(List<Point> source, float zoom) {
    int time = 0;
    for (int i = 0; i < source.size() - 1; i++) {
      Point point = source.get(i), next = source.get(i + 1);
      if (zoom >= Math.min(point.zoom(), next.zoom())
          && zoom <= Math.max(point.zoom(), next.zoom())) {
        if (point.zoom() == next.zoom()) return time;
        return time
            + Math.round(
                point.delayMillis() * (zoom - point.zoom()) / (next.zoom() - point.zoom()));
      }
      time += point.delayMillis();
    }
    return time;
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
