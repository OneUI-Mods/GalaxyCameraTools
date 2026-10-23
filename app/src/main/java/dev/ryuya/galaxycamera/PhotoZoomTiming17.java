package dev.ryuya.galaxycamera;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.os.Handler;
import android.view.Choreographer;
import android.view.View;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Samples Samsung's original nonlinear zoom curve at display time, without delayed-step backlog.
 */
final class PhotoZoomTiming17 {
  private final HostRuntime runtime;
  private final CameraHooks17 camera;
  private final ThreadLocal<Selection> selecting = new ThreadLocal<>();
  private Constructor<?> nativeStep;
  private Class<?> settingKey;
  private Object zoomKey;
  private Motion motion;
  private WeakReference<ValueAnimator> nativeAnimator = new WeakReference<>(null);

  PhotoZoomTiming17(HostRuntime runtime, CameraHooks17 camera) {
    this.runtime = runtime;
    this.camera = camera;
  }

  void install() throws Exception {
    Class<?> zoomView =
        Class.forName(
            "com.sec.android.app.camera.layer.keyscreen.zoom.ZoomView", false, runtime.loader);
    ModernHook.findAndHookMethod(
        zoomView,
        "initialize",
        new ModernHook.Callback() {
          protected void afterHookedMethod(ModernHook.Param parameter) {
            try {
              Object source = Reflect.get(parameter.thisObject, "k");
              String curves =
                  StockZoomCurves.encode((java.util.Map<?, ?>) Reflect.get(source, "a"));
              runtime.worker.post(
                  () -> {
                    try {
                      runtime
                          .context
                          .getContentResolver()
                          .call(ConfigProvider.URI, "zoom_curves_publish", curves, null);
                    } catch (RuntimeException error) {
                      ModernHook.log("Stock curve sharing unavailable");
                    }
                  });
            } catch (ReflectiveOperationException | org.json.JSONException error) {
              ModernHook.log("Stock curve sharing unavailable");
            }
          }
        });
    Class<?> stepType = Class.forName("v9.m", false, runtime.loader);
    nativeStep = stepType.getDeclaredConstructor(zoomView, long.class, ArrayList.class, int.class);
    nativeStep.setAccessible(true);
    settingKey = Class.forName("l9.f0", false, runtime.loader);
    zoomKey = Reflect.value(settingKey, "ZOOM_VALUE");
    ModernHook.hookAllConstructors(
        stepType,
        new ModernHook.Callback() {
          protected void afterHookedMethod(ModernHook.Param parameter) {
            Selection selection = selecting.get();
            if (selection != null
                && parameter.args[0] == selection.view
                && (int) parameter.args[3] == 1) {
              selection.runner = (Runnable) parameter.thisObject;
              selection.generation = (long) parameter.args[1];
              selection.points = (ArrayList<?>) parameter.args[2];
            }
          }
        });
    // The stock SUPER_STEADY category bypasses H2's animation entirely.
    // Limit this override to an actual H2 invocation, never capability or touch checks.
    ModernHook.findAndHookMethod(
        zoomView,
        "x2",
        new ModernHook.Callback() {
          protected void beforeHookedMethod(ModernHook.Param call) {
            Selection active = selecting.get();
            if (active != null && active.view == call.thisObject && active.steady)
              call.setResult(true);
          }
        });
    Class<?> nativeListener = Class.forName("v9.p", false, runtime.loader);
    ModernHook.findAndHookMethod(
        nativeListener,
        "onAnimationStart",
        Animator.class,
        new ModernHook.Callback() {
          protected void beforeHookedMethod(ModernHook.Param call) throws Exception {
            Selection active = selecting.get();
            if (active == null
                || Reflect.get(call.thisObject, "b") != active.view
                || (int) Reflect.get(call.thisObject, "a") != 0
                || !(call.args[0] instanceof ValueAnimator animator)) return;
            animator.setDuration(LensMotionTiming.duration((int) animator.getDuration()));
            animator.setInterpolator(LensMotionTiming::phase);
            nativeAnimator = new WeakReference<>(animator);
            ModernHook.log(
                "Lens motion: native animator; mode="
                    + active.mode
                    + "; steady="
                    + active.steady
                    + "; duration="
                    + animator.getDuration());
          }
        });
    ModernHook.deoptimize(Reflect.method(zoomView, "H2", int.class, int.class));
    ModernHook.aroundMethod(
        Reflect.method(zoomView, "H2", int.class, int.class),
        chain -> {
          int mode = camera.mode();
          if (!runtime.enabled(ConfigProvider.LENS_MOTION) || (mode != 0 && mode != 1))
            return chain.proceed();
          if (motion != null) motion.returnToNative();
          View view = (View) chain.getThisObject();
          boolean steady =
              mode == 1 && "SUPER_STEADY".equals(String.valueOf(Reflect.get(view, "f")));
          Selection selection = new Selection(view, mode, steady);
          selecting.set(selection);
          Object result;
          try {
            camera.beginZoomMeasurement();
            result = chain.proceed();
          } finally {
            selecting.remove();
          }
          // Non-table transitions already use Samsung's own eased ValueAnimator.
          if (selection.runner != null) {
            try {
              Motion next = new Motion(selection);
              ((Handler) Reflect.get(selection.view, "I")).removeCallbacks(selection.runner);
              runtime.guard.arm();
              selection.generation++;
              Reflect.field(selection.view.getClass(), "l")
                  .setInt(selection.view, (int) selection.generation);
              motion = next;
              next.start();
            } catch (ReflectiveOperationException | IllegalArgumentException error) {
              ModernHook.log("Photo zoom: retaining native timing: " + error);
            }
          }
          return result;
        });
    ModernHook.findAndHookMethod(
        zoomView,
        "clear",
        new ModernHook.Callback() {
          protected void beforeHookedMethod(ModernHook.Param parameter) {
            if (motion != null && motion.selection.view == parameter.thisObject) motion.stop();
          }
        });
    runtime.onChanged(
        () -> {
          if (!runtime.enabled(ConfigProvider.LENS_MOTION)) {
            if (motion != null) motion.returnToNative();
            ValueAnimator current = nativeAnimator.get();
            if (current != null && current.isRunning()) current.end();
          }
        });
  }

  private static final class Selection {
    final View view;
    final int mode;
    final boolean steady;
    Runnable runner;
    ArrayList<?> points;
    long generation;

    Selection(View view, int mode, boolean steady) {
      this.view = view;
      this.mode = mode;
      this.steady = steady;
    }
  }

  private final class Motion implements Choreographer.FrameCallback {
    final Selection selection;
    final List<ZoomCurve.Point> curve = new ArrayList<>();
    final Object settings;
    final Handler nativeHandler;
    final float previousFrameRate;
    final int duration, nativeDuration;
    final Field generationField, runningField;
    final Method writeZoom, updateButtons, updateLabel;
    long started;
    int lastZoom = Integer.MIN_VALUE, frames;
    boolean stopped;

    Motion(Selection selection) throws ReflectiveOperationException {
      this.selection = selection;
      for (Object point : selection.points) {
        curve.add(
            new ZoomCurve.Point((float) Reflect.get(point, "a"), (int) Reflect.get(point, "b")));
      }
      nativeDuration = ZoomCurve.duration(curve);
      duration = LensMotionTiming.duration(nativeDuration);
      if (nativeDuration <= 0 || nativeDuration > 3000)
        throw new IllegalArgumentException("Unknown curve duration");
      settings = Reflect.get(Reflect.get(selection.view, "b"), "d");
      nativeHandler = (Handler) Reflect.get(selection.view, "I");
      generationField = Reflect.field(selection.view.getClass(), "l");
      runningField = Reflect.field(selection.view.getClass(), "p");
      writeZoom = Reflect.method(settings.getClass(), "V", int.class, settingKey);
      updateButtons = Reflect.method(selection.view.getClass(), "C2", int.class);
      updateLabel = Reflect.method(selection.view.getClass(), "N2", int.class);
      previousFrameRate = selection.view.getRequestedFrameRate();
    }

    void start() {
      started = System.nanoTime();
      selection.view.setRequestedFrameRate(60f);
      Choreographer.getInstance().postFrameCallback(this);
      ModernHook.log(
          "Lens motion: eased curve, display synchronized; mode="
              + selection.mode
              + "; steady="
              + selection.steady
              + "; duration="
              + duration);
    }

    @Override
    public void doFrame(long frameTimeNanos) {
      if (stopped) return;
      try {
        if (generationField.getInt(selection.view) != selection.generation
            || !runningField.getBoolean(selection.view)) {
          stop();
          return;
        }
        if (!runtime.enabled(ConfigProvider.LENS_MOTION) || camera.mode() != selection.mode) {
          returnToNative();
          return;
        }
        int elapsed = (int) Math.max(0, (frameTimeNanos - started) / 1_000_000);
        int value =
            Math.round(
                ZoomCurve.valueAt(
                        curve, LensMotionTiming.curveTime(elapsed, duration, nativeDuration))
                    * 1000);
        if (value != lastZoom) {
          writeZoom.invoke(settings, value, zoomKey);
          updateButtons.invoke(selection.view, value);
          updateLabel.invoke(selection.view, value);
          lastZoom = value;
          frames++;
        }
        if (elapsed >= duration) {
          stop();
          ((Handler) Reflect.get(selection.view, "L"))
              .post((Runnable) Reflect.get(selection.view, "S"));
          ModernHook.log("Photo zoom: completed; distinct display steps=" + frames);
        } else {
          Choreographer.getInstance().postFrameCallback(this);
        }
      } catch (Throwable error) {
        returnToNative();
        runtime.fail(error);
      }
    }

    void returnToNative() {
      if (stopped) return;
      stop();
      try {
        if (generationField.getInt(selection.view) != selection.generation
            || !runningField.getBoolean(selection.view)) return;
        int elapsed = (int) Math.max(0, (System.nanoTime() - started) / 1_000_000);
        int index =
            ZoomCurve.nextIndex(
                curve, LensMotionTiming.curveTime(elapsed, duration, nativeDuration));
        Runnable continuation =
            (Runnable)
                nativeStep.newInstance(
                    selection.view, selection.generation, selection.points, index);
        nativeHandler.post(continuation);
      } catch (Throwable error) {
        runtime.fail(error);
      }
    }

    void stop() {
      stopped = true;
      Choreographer.getInstance().removeFrameCallback(this);
      selection.view.setRequestedFrameRate(previousFrameRate);
      if (motion == this) motion = null;
    }
  }
}
