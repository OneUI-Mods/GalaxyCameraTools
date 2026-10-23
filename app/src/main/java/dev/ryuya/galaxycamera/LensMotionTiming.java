package dev.ryuya.galaxycamera;

/** A visible, gentle acceleration and deceleration shared by Camera and Expert RAW. */
final class LensMotionTiming {
  static int duration(int nativeMillis) {
    return Math.max(320, Math.min(1100, Math.round(nativeMillis * 1.35f)));
  }

  static float phase(float fraction) {
    float t = Math.max(0f, Math.min(1f, fraction));
    return t * t * (3f - 2f * t);
  }

  static int curveTime(int elapsed, int duration, int nativeDuration) {
    return Math.round(phase(elapsed / (float) duration) * nativeDuration);
  }
}
