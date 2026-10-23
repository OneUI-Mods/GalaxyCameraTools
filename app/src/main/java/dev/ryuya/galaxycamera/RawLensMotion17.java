package dev.ryuya.galaxycamera;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.util.SizeF;
import android.view.SurfaceView;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;
import java.util.List;

/** Visual lens handoff; never changes RAW capture requests, crop regions or saved settings. */
final class RawLensMotion17 {
  private final HostRuntime runtime;
  private final Object layer;
  private final View snapshot, blur;
  private final SurfaceView preview;
  private final Runnable completed;
  private final float ratio;
  private final List<ZoomCurve.Point> stockCurve;
  private final float previewScaleX, previewScaleY, previewPivotX, previewPivotY;
  private final float previewFrameRate, snapshotFrameRate;
  private final Rect previewClip;
  private final Runnable timeout = this::finish;
  private ValueAnimator zoom, fade;
  private boolean previewReady, travelFinished, fadeFinished, finished;
  private float progress;

  RawLensMotion17(
      HostRuntime runtime, Object context, Object layer, int from, int to, Runnable completed)
      throws Exception {
    this.runtime = runtime;
    this.layer = layer;
    this.completed = completed;
    stockCurve = StockZoomCurves.read(runtime, from, to);
    Object binding = Reflect.get(layer, "mViewBinding");
    snapshot = (View) Reflect.get(binding, "h");
    blur = (View) Reflect.get(binding, "g");
    preview =
        (SurfaceView) Reflect.call(Reflect.call(context, "getPreviewManager"), "getSurfaceView");
    if (preview == null || preview.getWidth() <= 0 || preview.getHeight() <= 0)
      throw new IllegalStateException("Preview surface is not laid out");
    ratio = fieldOfViewScale(runtime, to) / fieldOfViewScale(runtime, from);
    if (!Float.isFinite(ratio) || ratio < 0.05f || ratio > 20f)
      throw new IllegalStateException("Unknown lens field of view");
    previewScaleX = preview.getScaleX();
    previewScaleY = preview.getScaleY();
    previewPivotX = preview.getPivotX();
    previewPivotY = preview.getPivotY();
    previewClip = preview.getClipBounds();
    previewFrameRate = preview.getRequestedFrameRate();
    snapshotFrameRate = snapshot.getRequestedFrameRate();
  }

  boolean owns(Object candidate) {
    return !finished && layer == candidate;
  }

  void start() {
    blur.setAlpha(1f);
    blur.setVisibility(ratio < 1f ? View.VISIBLE : View.GONE);
    snapshot.setVisibility(View.VISIBLE);
    snapshot.setAlpha(1f);
    snapshot.setPivotX(snapshot.getWidth() / 2f);
    snapshot.setPivotY(snapshot.getHeight() / 2f);
    snapshot.setRequestedFrameRate(60f);
    preview.setRequestedFrameRate(60f);
    runtime.main.postDelayed(timeout, 2000);
    startTravel();
    ModernHook.log(
        "Expert RAW lens motion: field-of-view ratio="
            + ratio
            + "; stock curve="
            + (stockCurve != null));
  }

  void onPreviewReady() {
    if (finished || previewReady) return;
    previewReady = true;
    if (ratio < 1f) {
      transformPreview(1f + (1f / ratio - 1f) * (1f - progress));
      startFade();
    } else if (travelFinished) {
      startFade();
    }
  }

  private void startTravel() {
    zoom = ValueAnimator.ofFloat(0f, 1f);
    // Prefer the exact numeric curve read from the installed stock Camera at runtime.
    zoom.setInterpolator(
        stockCurve == null ? new PathInterpolator(0.22f, 0.25f, 0f, 1f) : new LinearInterpolator());
    zoom.setDuration(
        stockCurve == null
            ? (Math.min(ratio, 1f / ratio) < 0.34f ? 800 : 200)
            : ZoomCurve.duration(stockCurve));
    zoom.addUpdateListener(
        value -> {
          if (finished) return;
          progress = (float) value.getAnimatedValue();
          if (stockCurve != null) {
            float first = stockCurve.get(0).zoom();
            float last = stockCurve.get(stockCurve.size() - 1).zoom();
            float current =
                ZoomCurve.valueAt(stockCurve, Math.round(progress * zoom.getDuration()));
            progress = Math.max(0f, Math.min(1f, (current - first) / (last - first)));
          }
          if (ratio > 1f) {
            float scale = 1f + (ratio - 1f) * progress;
            snapshot.setScaleX(scale);
            snapshot.setScaleY(scale);
          } else {
            float scale = 1f + (ratio - 1f) * progress;
            snapshot.setScaleX(scale);
            snapshot.setScaleY(scale);
            if (previewReady) transformPreview(1f + (1f / ratio - 1f) * (1f - progress));
          }
        });
    zoom.addListener(
        new AnimatorListenerAdapter() {
          @Override
          public void onAnimationEnd(Animator animation) {
            if (finished) return;
            travelFinished = true;
            if (ratio < 1f) {
              if (fadeFinished) finish();
            } else if (previewReady) startFade();
          }
        });
    zoom.start();
  }

  private void transformPreview(float scale) {
    preview.setPivotX(preview.getWidth() / 2f);
    preview.setPivotY(preview.getHeight() / 2f);
    preview.setScaleX(previewScaleX * scale);
    preview.setScaleY(previewScaleY * scale);
    int insetX = Math.round(preview.getWidth() * (1f - 1f / scale) / 2f);
    int insetY = Math.round(preview.getHeight() * (1f - 1f / scale) / 2f);
    Rect clip = new Rect(insetX, insetY, preview.getWidth() - insetX, preview.getHeight() - insetY);
    if (previewClip != null) clip.intersect(previewClip);
    preview.setClipBounds(clip);
  }

  private void startFade() {
    if (finished || fade != null) return;
    fade = ValueAnimator.ofFloat(snapshot.getAlpha(), 0f);
    fade.setDuration(90);
    fade.addUpdateListener(
        value -> {
          float alpha = (float) value.getAnimatedValue();
          snapshot.setAlpha(alpha);
          blur.setAlpha(alpha);
        });
    fade.addListener(
        new AnimatorListenerAdapter() {
          @Override
          public void onAnimationEnd(Animator animation) {
            fadeFinished = true;
            if (!finished && (ratio >= 1f || travelFinished)) finish();
          }
        });
    fade.start();
  }

  void finish() {
    if (finished) return;
    finished = true;
    runtime.main.removeCallbacks(timeout);
    if (zoom != null) zoom.cancel();
    if (fade != null) fade.cancel();
    preview.setScaleX(previewScaleX);
    preview.setScaleY(previewScaleY);
    preview.setPivotX(previewPivotX);
    preview.setPivotY(previewPivotY);
    preview.setClipBounds(previewClip);
    preview.setRequestedFrameRate(previewFrameRate);
    snapshot.setScaleX(1f);
    snapshot.setScaleY(1f);
    snapshot.setAlpha(1f);
    snapshot.setRequestedFrameRate(snapshotFrameRate);
    snapshot.setVisibility(View.GONE);
    blur.setAlpha(1f);
    blur.setVisibility(View.GONE);
    try {
      Reflect.call(layer, "cancelPreviewAnimation");
    } catch (ReflectiveOperationException error) {
      runtime.fail(error);
    }
    completed.run();
    ModernHook.log("Expert RAW lens motion: restored; new preview=" + previewReady);
  }

  private static float fieldOfViewScale(HostRuntime runtime, int cameraId) throws Exception {
    Class<?> ids =
        Class.forName(
            "com.samsung.android.app.galaxyraw.engine.request.CameraId", false, runtime.loader);
    int deviceId = (int) Reflect.method(ids, "getDeviceId", int.class).invoke(null, cameraId);
    CameraManager manager = runtime.context.getSystemService(CameraManager.class);
    CameraCharacteristics characteristics =
        manager.getCameraCharacteristics(Integer.toString(deviceId));
    float[] focalLengths =
        characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
    SizeF sensor = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
    if (focalLengths == null
        || focalLengths.length == 0
        || sensor == null
        || sensor.getWidth() <= 0) throw new IllegalStateException("Lens geometry is unavailable");
    return focalLengths[0] / sensor.getWidth();
  }
}
