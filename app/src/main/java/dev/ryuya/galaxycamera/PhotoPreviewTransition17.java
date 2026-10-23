package dev.ryuya.galaxycamera;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * One preview copy per transition; geometric motion is rendered independently of camera cadence.
 */
final class PhotoPreviewTransition17 {
  private static final CaptureRequest.Key<Float> ZOOM =
      new CaptureRequest.Key<>("samsung.android.scaler.zoomRatio", Float.class);
  private final HostRuntime runtime;
  private final SurfaceView preview;
  private final FrameLayout parent;
  private final float from, to;
  private final Runnable completed;
  private final float scaleX, scaleY, pivotX, pivotY, frameRate;
  private final Rect originalClip;
  private final Rect animatedClip = new Rect();
  private final Runnable copyTimeout;
  private final Runnable handoffTimeout;
  private final int width, height;
  private Runnable failure;
  private FrameLayout cover;
  private ImageView snapshot;
  private ValueAnimator fade;
  private boolean copyPending = true, ready, travelFinished, fadeFinished;
  private volatile boolean copied;
  private volatile boolean closed;
  private int matchingFrames;
  private float current;
  private boolean ownsPreview;

  PhotoPreviewTransition17(
      HostRuntime runtime, SurfaceView preview, float from, float to, Runnable completed) {
    if (preview == null
        || !preview.isAttachedToWindow()
        || !preview.getHolder().getSurface().isValid()
        || !(preview.getParent() instanceof FrameLayout)
        || preview.getWidth() <= 0
        || preview.getHeight() <= 0
        || !Float.isFinite(from)
        || !Float.isFinite(to)
        || from <= 0
        || to <= 0
        || from == to) throw new IllegalArgumentException("Preview cannot be transformed");
    this.runtime = runtime;
    this.preview = preview;
    this.parent = (FrameLayout) preview.getParent();
    this.from = from;
    this.to = to;
    this.current = from;
    this.completed = completed;
    width = preview.getWidth();
    height = preview.getHeight();
    scaleX = preview.getScaleX();
    scaleY = preview.getScaleY();
    pivotX = preview.getPivotX();
    pivotY = preview.getPivotY();
    originalClip = preview.getClipBounds();
    frameRate = preview.getRequestedFrameRate();
    copyTimeout = this::copyFailed;
    handoffTimeout = this::finish;
  }

  /**
   * Capture before submitting the final lens request. No bitmap is copied in the animation loop.
   */
  void capture(Runnable captured, Runnable fallback) {
    failure = fallback;
    float reduction = Math.min(1f, 1280f / Math.max(width, height));
    final Bitmap pixels;
    try {
      pixels =
          Bitmap.createBitmap(
              Math.max(1, Math.round(width * reduction)),
              Math.max(1, Math.round(height * reduction)),
              Bitmap.Config.ARGB_8888);
    } catch (OutOfMemoryError | IllegalArgumentException unavailable) {
      copyFailed();
      return;
    }
    runtime.main.postDelayed(copyTimeout, 250);
    try {
      PixelCopy.request(
          preview,
          pixels,
          result -> {
            if (closed || !copyPending) {
              pixels.recycle();
              return;
            }
            if (result != PixelCopy.SUCCESS || !samePreview()) {
              pixels.recycle();
              copyFailed();
              return;
            }
            runtime.main.removeCallbacks(copyTimeout);
            try {
              cover = new FrameLayout(preview.getContext());
              cover.setLayerType(View.LAYER_TYPE_HARDWARE, null);
              cover.setClipChildren(true);
              cover.setClipToPadding(true);
              cover.setImportantForAccessibility(
                  View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
              if (to < from) {
                ImageView background = image(pixels);
                background.setRenderEffect(
                    RenderEffect.createBlurEffect(18f, 18f, Shader.TileMode.CLAMP));
                cover.addView(background, new FrameLayout.LayoutParams(-1, -1));
              }
              snapshot = image(pixels);
              if (to < from) softenEdges(snapshot);
              cover.addView(snapshot, new FrameLayout.LayoutParams(-1, -1));
              FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(width, height);
              position.leftMargin = preview.getLeft();
              position.topMargin = preview.getTop();
              parent.addView(cover, position);
              cover.setRequestedFrameRate(60f);
              ownsPreview = true;
              preview.setRequestedFrameRate(60f);
              copyPending = false;
              copied = true;
              runtime.main.postDelayed(handoffTimeout, 2000);
              ModernHook.log("Photo display handoff: snapshot ready");
              captured.run();
            } catch (RuntimeException unavailable) {
              // A pause or configuration change can invalidate the captured surface.
              copyPending = true;
              copyFailed();
            }
          },
          runtime.main);
    } catch (IllegalArgumentException unavailable) {
      pixels.recycle();
      copyFailed();
    }
  }

  private boolean samePreview() {
    return preview.isAttachedToWindow()
        && preview.getParent() == parent
        && preview.getHolder().getSurface().isValid()
        && preview.getWidth() == width
        && preview.getHeight() == height;
  }

  private ImageView image(Bitmap pixels) {
    ImageView image = new ImageView(preview.getContext());
    image.setScaleType(ImageView.ScaleType.FIT_XY);
    image.setImageBitmap(pixels);
    image.setClickable(false);
    image.setFocusable(false);
    return image;
  }

  private void copyFailed() {
    if (closed || !copyPending) return;
    copyPending = false;
    close();
    ModernHook.log("Photo display handoff: native fallback");
    failure.run();
  }

  void sample(float zoom, boolean last) {
    if (closed || !copied) return;
    if (!samePreview()) {
      finish();
      return;
    }
    current = zoom;
    float oldScale = LensPreviewGeometry.snapshotScale(from, current);
    snapshot.setPivotX(preview.getWidth() / 2f);
    snapshot.setPivotY(preview.getHeight() / 2f);
    snapshot.setScaleX(oldScale);
    snapshot.setScaleY(oldScale);
    if (ready && to < from) transformPreview();
    if (last) {
      travelFinished = true;
      maybeFade();
      if (fadeFinished) finish();
    }
  }

  /**
   * Runs on the camera callback thread; wait for successive frames containing the final request.
   */
  void observe(TotalCaptureResult result) {
    if (closed || !copied) return;
    Float zoom;
    try {
      zoom = result.getRequest().get(ZOOM);
    } catch (IllegalArgumentException unavailable) {
      return;
    }
    if (zoom == null || Math.abs(zoom - to) > 0.001f) {
      matchingFrames = 0;
      return;
    }
    if (++matchingFrames == 2)
      runtime.main.postDelayed(
          () -> {
            if (closed) return;
            ready = true;
            if (to < from) transformPreview();
            maybeFade();
          },
          40);
  }

  private void transformPreview() {
    float scale = LensPreviewGeometry.incomingScale(to, current);
    preview.setPivotX(preview.getWidth() / 2f);
    preview.setPivotY(preview.getHeight() / 2f);
    preview.setScaleX(scaleX * scale);
    preview.setScaleY(scaleY * scale);
    int x = Math.round(preview.getWidth() * (1f - 1f / scale) / 2f);
    int y = Math.round(preview.getHeight() * (1f - 1f / scale) / 2f);
    animatedClip.set(x, y, preview.getWidth() - x, preview.getHeight() - y);
    if (originalClip != null) animatedClip.intersect(originalClip);
    preview.setClipBounds(animatedClip);
  }

  private void maybeFade() {
    if (closed || !ready || fade != null || (to > from && !travelFinished)) return;
    fade = ValueAnimator.ofFloat(1f, 0f);
    fade.setDuration(90);
    fade.addUpdateListener(animation -> cover.setAlpha((float) animation.getAnimatedValue()));
    fade.addListener(
        new AnimatorListenerAdapter() {
          @Override
          public void onAnimationEnd(Animator animation) {
            if (closed) return;
            fadeFinished = true;
            if (travelFinished) finish();
          }
        });
    fade.start();
  }

  private void finish() {
    if (closed) return;
    close();
    ModernHook.log("Photo display handoff: restored; new preview=" + ready);
    completed.run();
  }

  /** A single GPU mask blends the shrinking picture into its blurred surround. */
  private void softenEdges(ImageView picture) {
    RuntimeShader mask =
        new RuntimeShader(
            """
            uniform shader content;
            uniform float2 size;
            uniform float edge;
            half4 main(float2 point) {
              float2 distance = min(point, size - point);
              float alpha = smoothstep(0.0, edge, min(distance.x, distance.y));
              return content.eval(point) * half(alpha);
            }
            """);
    mask.setFloatUniform("size", width, height);
    mask.setFloatUniform("edge", Math.min(width, height) * 0.08f);
    picture.setRenderEffect(RenderEffect.createRuntimeShaderEffect(mask, "content"));
  }

  void close() {
    if (closed) return;
    closed = true;
    runtime.main.removeCallbacks(copyTimeout);
    runtime.main.removeCallbacks(handoffTimeout);
    if (fade != null) fade.cancel();
    if (ownsPreview) {
      preview.setScaleX(scaleX);
      preview.setScaleY(scaleY);
      preview.setPivotX(pivotX);
      preview.setPivotY(pivotY);
      preview.setClipBounds(originalClip);
      if (preview.getRequestedFrameRate() == 60f) preview.setRequestedFrameRate(frameRate);
    }
    if (cover != null) {
      parent.removeView(cover);
      // Detached display lists may still reference the pixels. Let Bitmap's ownership release them.
      cover.removeAllViews();
      cover = null;
      snapshot = null;
    }
  }
}
