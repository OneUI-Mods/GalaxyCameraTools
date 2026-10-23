package dev.ryuya.galaxycamera;

import java.util.ArrayList;
import java.util.List;

/** Subdivides long native zoom steps without changing their curve or total travel time. */
final class ZoomCurve {
  record Point(float zoom, int delayMillis) {}

  static List<Point> atSixtyHz(List<Point> source) {
    if (source.size() < 2 || source.size() > 1000) return source;
    List<Point> result = new ArrayList<>();
    for (int index = 0; index < source.size() - 1; index++) {
      Point current = source.get(index);
      Point next = source.get(index + 1);
      if (!Float.isFinite(current.zoom())
          || current.zoom() <= 0
          || current.delayMillis() <= 0
          || current.delayMillis() > 1000) return source;
      int pieces = (current.delayMillis() + 16) / 17;
      for (int part = 0; part < pieces; part++) {
        int start = part * current.delayMillis() / pieces;
        int end = (part + 1) * current.delayMillis() / pieces;
        float zoom =
            current.zoom() + (next.zoom() - current.zoom()) * start / current.delayMillis();
        result.add(new Point(zoom, end - start));
      }
    }
    Point last = source.get(source.size() - 1);
    if (!Float.isFinite(last.zoom()) || last.zoom() <= 0 || last.delayMillis() != 0) return source;
    result.add(last);
    return result;
  }
}
