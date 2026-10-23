package dev.ryuya.galaxycamera;

/** A 60 Hz display is not evidence that the camera stream can deliver 60 fps. */
final class PreviewPolicy {
  static boolean eligible(
      boolean enabled,
      int mode,
      int makerType,
      boolean supportsFixed60,
      long minimumFrameDuration,
      int thermalStatus) {
    return enabled
        && mode == 0
        && makerType == 1
        && supportsFixed60
        && minimumFrameDuration > 0
        && minimumFrameDuration <= 16_666_667L
        && thermalStatus < 3;
  }
}
