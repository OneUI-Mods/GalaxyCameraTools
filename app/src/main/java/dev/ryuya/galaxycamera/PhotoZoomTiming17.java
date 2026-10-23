package dev.ryuya.galaxycamera;

import android.os.Handler;
import android.view.Choreographer;
import android.view.View;
import java.lang.reflect.Constructor;
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

  PhotoZoomTiming17(HostRuntime runtime, CameraHooks17 camera) {
    this.runtime = runtime;
    this.camera = camera;
  }

  void install() throws Exception {
    Class<?> zoomView =
        Class.forName(
            "com.sec.android.app.camera.layer.keyscreen.zoom.ZoomView", false, runtime.loader);
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
    ModernHook.aroundMethod(
        Reflect.method(zoomView, "H2", int.class, int.class),
        chain -> {
          if (!runtime.enabled(ConfigProvider.FPS) || camera.mode() != 0) return chain.proceed();
          if (motion != null) motion.returnToNative();
          Selection selection = new Selection((View) chain.getThisObject());
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
          if (motion != null && !runtime.enabled(ConfigProvider.FPS)) motion.returnToNative();
        });
  }

  private static final class Selection {
    final View view;
    Runnable runner;
    ArrayList<?> points;
    long generation;

    Selection(View view) {
      this.view = view;
    }
  }

  private final class Motion implements Choreographer.FrameCallback {
    final Selection selection;
    final List<ZoomCurve.Point> curve = new ArrayList<>();
    final Object settings;
    final Handler nativeHandler;
    final float previousFrameRate;
    final int duration;
    long started;
    int lastZoom = Integer.MIN_VALUE, frames;
    boolean stopped;

    Motion(Selection selection) throws ReflectiveOperationException {
      this.selection = selection;
      for (Object point : selection.points) {
        curve.add(
            new ZoomCurve.Point((float) Reflect.get(point, "a"), (int) Reflect.get(point, "b")));
      }
      duration = ZoomCurve.duration(curve);
      if (duration <= 0 || duration > 3000)
        throw new IllegalArgumentException("Unknown curve duration");
      settings = Reflect.get(Reflect.get(selection.view, "b"), "d");
      nativeHandler = (Handler) Reflect.get(selection.view, "I");
      previousFrameRate = selection.view.getRequestedFrameRate();
    }

    void start() {
      started = System.nanoTime();
      selection.view.setRequestedFrameRate(60f);
      Choreographer.getInstance().postFrameCallback(this);
      ModernHook.log("Photo zoom: eased native curve, display synchronized; duration=" + duration);
    }

    @Override
    public void doFrame(long frameTimeNanos) {
      if (stopped) return;
      try {
        if ((long) Reflect.get(selection.view, "l") != selection.generation
            || !(boolean) Reflect.get(selection.view, "p")) {
          stop();
          return;
        }
        if (!runtime.enabled(ConfigProvider.FPS) || camera.mode() != 0) {
          returnToNative();
          return;
        }
        int elapsed = (int) Math.max(0, (frameTimeNanos - started) / 1_000_000);
        int value = Math.round(ZoomCurve.valueAt(curve, elapsed) * 1000);
        if (value != lastZoom) {
          Reflect.method(settings.getClass(), "V", int.class, settingKey)
              .invoke(settings, value, zoomKey);
          Reflect.method(selection.view.getClass(), "C2", int.class).invoke(selection.view, value);
          Reflect.method(selection.view.getClass(), "N2", int.class).invoke(selection.view, value);
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
        if ((long) Reflect.get(selection.view, "l") != selection.generation
            || !(boolean) Reflect.get(selection.view, "p")) return;
        int elapsed = (int) Math.max(0, (System.nanoTime() - started) / 1_000_000);
        int index = ZoomCurve.nextIndex(curve, elapsed);
        Runnable continuation =
            (Runnable)
                nativeStep.newInstance(
                    selection.view, selection.generation, selection.points, index);
        nativeHandler.post(continuation);
      } catch (ReflectiveOperationException error) {
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
