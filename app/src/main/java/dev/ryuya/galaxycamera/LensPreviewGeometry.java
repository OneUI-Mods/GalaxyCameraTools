package dev.ryuya.galaxycamera;

/** Aligns the outgoing picture and incoming live view to the same interpolated field of view. */
final class LensPreviewGeometry {
  static float snapshotScale(float from, float current) {
    return current / from;
  }

  static float incomingScale(float target, float current) {
    return Math.max(1f, current / target);
  }
}
