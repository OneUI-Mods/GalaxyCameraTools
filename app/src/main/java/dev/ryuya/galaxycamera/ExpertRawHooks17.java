package dev.ryuya.galaxycamera;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Looper;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Reuses Expert RAW's own snapshot, blur and first-preview-frame handoff. */
final class ExpertRawHooks17 {
  private static final String BASE = "com.samsung.android.app.galaxyraw.";
  private final HostRuntime rt;
  private final ThreadLocal<Boolean> original = ThreadLocal.withInitial(() -> false);
  private Pending pending;
  private RawLensMotion17 motion;
  private Integer queuedLens;
  private Class<?> animationType, callbackType, engineState;
  private Object previewChange, previewing;
  private Method switchCamera, request;

  ExpertRawHooks17(HostRuntime runtime) {
    rt = runtime;
  }

  void install() throws Exception {
    ClassLoader l = rt.loader;
    Class<?> engine = Class.forName(BASE + "engine.CommonEngine", false, l);
    Class<?> animation =
        Class.forName(BASE + "layer.previewanimation.PreviewAnimationLayerView", false, l);
    animationType =
        Class.forName(
            BASE + "interfaces.PreviewAnimationLayerManager$PreviewAnimationType", false, l);
    callbackType =
        Class.forName(
            BASE + "interfaces.PreviewAnimationLayerManager$PreviewSnapshotEventListener",
            false,
            l);
    engineState = Class.forName(BASE + "interfaces.Engine$State", false, l);
    previewChange = Reflect.value(animationType, "PREVIEW_CHANGE");
    previewing = Reflect.value(engineState, "PREVIEWING");
    request =
        Reflect.method(
            animation, "requestPreviewAnimation", animationType, callbackType, Rect.class);
    switchCamera = Reflect.method(engine, "switchCamera", int.class);
    Class<?> presenter =
        Class.forName(BASE + "shootingmode.abstraction.AbstractShootingModePresenter", false, l);
    ModernHook.findAndHookMethod(
        presenter,
        "switchCamera",
        int.class,
        new ModernHook.Callback() {
          protected void beforeHookedMethod(ModernHook.Param p) {
            if (!rt.enabled(ConfigProvider.ANIMATION)
                || Looper.myLooper() != Looper.getMainLooper()) return;
            try {
              Object source = Reflect.get(p.thisObject, "mEngine");
              if (prepare(source, (int) p.args[0])) {
                Reflect.call(Reflect.call(source, "getAeAfManager"), "resetAeAfAwb");
                p.setResult(null);
              }
            } catch (Throwable e) {
              rt.fail(e);
            }
          }
        });
    ModernHook.deoptimize(
        Reflect.method(
            Class.forName(BASE + "shootingmode.pro.ProPresenter", false, l),
            "handleLensTypeChanged",
            int.class,
            int.class));
    Class<?> pro = Class.forName(BASE + "shootingmode.pro.ProPresenter", false, l);
    Class<?> settingKey = Class.forName(BASE + "interfaces.CameraSettings$Key", false, l);
    for (Method caller :
        new Method[] {
          Reflect.method(
              pro,
              "lambda$initializeSettingChangeConsumerMap$53",
              settingKey,
              int.class,
              int.class),
          Reflect.method(pro, "onCameraSettingChanged", settingKey, int.class, int.class),
          Reflect.method(
              Class.forName(BASE + "shootingmode.pro.j", false, l),
              "onCameraSettingChanged",
              settingKey,
              int.class,
              int.class),
          Reflect.method(presenter, "switchCamera", int.class)
        }) {
      ModernHook.deoptimize(caller);
    }
    ModernHook.hookMethod(
        switchCamera,
        new ModernHook.Callback() {
          protected void beforeHookedMethod(ModernHook.Param p) {
            if (original.get()
                || Looper.myLooper() != Looper.getMainLooper()
                || !rt.enabled(ConfigProvider.ANIMATION)) return;
            try {
              rt.report("EXPERT RAW: lens request " + p.args[0]);
              if (prepare(p.thisObject, (int) p.args[0])) p.setResult(null);
            } catch (Throwable e) {
              rt.fail(e);
            }
          }
        });
    ModernHook.findAndHookMethod(
        animation,
        "onPreviewSnapShotError",
        new ModernHook.Callback() {
          protected void afterHookedMethod(ModernHook.Param p) {
            if (pending != null && pending.layer == p.thisObject) pending.finish(false);
          }
        });
    ModernHook.findAndHookMethod(
        animation,
        "startPreviewChangeAnimation",
        new ModernHook.Callback() {
          protected void beforeHookedMethod(ModernHook.Param p) {
            if (motion != null && motion.owns(p.thisObject)) p.setResult(null);
          }
        });
    ModernHook.findAndHookMethod(
        animation,
        "hidePreviewAnimation",
        Rect.class,
        new ModernHook.Callback() {
          protected void beforeHookedMethod(ModernHook.Param p) {
            if (motion != null && motion.owns(p.thisObject)) {
              motion.onPreviewReady();
              p.setResult(null);
            }
          }
        });
    Class<?> raw = Class.forName(BASE + "GalaxyRaw", false, l);
    ModernHook.findAndHookMethod(
        raw,
        "onPause",
        new ModernHook.Callback() {
          protected void beforeHookedMethod(ModernHook.Param p) {
            queuedLens = null;
            if (pending != null) pending.cancel();
            if (motion != null) motion.finish();
          }
        });
    rt.onChanged(
        () -> {
          if (!rt.enabled(ConfigProvider.ANIMATION)) {
            queuedLens = null;
            if (pending != null) pending.finish(false);
            if (motion != null) motion.finish();
          }
        });
    rt.ready();
  }

  private boolean prepare(Object engine, int cameraId) throws Exception {
    if (motion != null) {
      queuedLens = cameraId;
      return true;
    }
    // Existing native facing / ratio / resolution animations keep their own callback.
    if (pending != null) {
      if (pending.engine == engine) {
        pending.cameraId = cameraId;
        return true;
      }
      pending.cancel();
    }
    Object context = Reflect.get(engine, "mCameraContext");
    if (!(boolean) Reflect.call(context, "isRunning")
        || (boolean) Reflect.call(context, "isCapturing")) return false;
    if (!(boolean)
        Reflect.method(engine.getClass(), "isCurrentState", engineState).invoke(engine, previewing))
      return false;
    Activity activity = (Activity) Reflect.call(context, "getActivity");
    if (activity.isFinishing() || activity.isDestroyed()) return false;
    Object layer =
        Reflect.call(Reflect.call(context, "getLayerManager"), "getPreviewAnimationLayerManager");
    if (!"NONE".equals(String.valueOf(Reflect.call(layer, "getCurrentAnimationType")))) {
      rt.report("EXPERT RAW: existing animation " + Reflect.call(layer, "getCurrentAnimationType"));
      return false;
    }
    Object settings = Reflect.call(context, "getCameraSettings");
    if ((int) Reflect.call(settings, "getCameraId") == cameraId) {
      rt.report("EXPERT RAW: settings already reference requested lens");
      return false;
    }
    Rect rect =
        (Rect) Reflect.call(Reflect.call(context, "getPreviewManager"), "getPreviewLayoutRect");
    if (rect == null || rect.isEmpty()) return false;
    rt.guard.arm();
    int oldCameraId = (int) Reflect.call(settings, "getCameraId");
    Pending next = new Pending(engine, cameraId, context, layer, oldCameraId);
    pending = next;
    Object callback =
        Proxy.newProxyInstance(
            rt.loader,
            new Class<?>[] {callbackType},
            (proxy, method, args) -> {
              if (method.getDeclaringClass() == Object.class)
                return objectMethod(proxy, method, args);
              if (method.getName().equals("onPreviewSnapshotShow")) next.finish(true);
              return null;
            });
    try {
      rt.main.postDelayed(next.timeout, 600);
      request.invoke(layer, previewChange, callback, new Rect(rect));
      rt.report("EXPERT RAW: waiting for preview snapshot");
      return true;
    } catch (Throwable error) {
      next.cancel();
      throw error;
    }
  }

  private static Object objectMethod(Object proxy, Method method, Object[] args) {
    return switch (method.getName()) {
      case "equals" -> proxy == args[0];
      case "hashCode" -> System.identityHashCode(proxy);
      default -> "Galaxy Camera Tools snapshot callback";
    };
  }

  private final class Pending {
    final Object engine, context, layer;
    final int oldCameraId;
    int cameraId;
    final AtomicBoolean done = new AtomicBoolean();
    final Runnable timeout = () -> finish(false);

    Pending(Object e, int id, Object c, Object view, int oldId) {
      oldCameraId = oldId;
      engine = e;
      cameraId = id;
      context = c;
      layer = view;
    }

    void cancel() {
      if (!done.compareAndSet(false, true)) return;
      rt.main.removeCallbacks(timeout);
      if (pending == this) pending = null;
      try {
        Reflect.call(layer, "cancelAllAnimator");
        Reflect.call(layer, "cancelPreviewAnimation");
      } catch (ReflectiveOperationException ignored) {
      }
    }

    void finish(boolean snapshot) {
      if (Looper.myLooper() != Looper.getMainLooper()) {
        rt.main.post(() -> finish(snapshot));
        return;
      }
      if (!done.compareAndSet(false, true)) return;
      rt.main.removeCallbacks(timeout);
      if (pending == this) pending = null;
      try {
        if (!snapshot) {
          Reflect.call(layer, "cancelAllAnimator");
          Reflect.call(layer, "cancelPreviewAnimation");
        }
        Activity activity = (Activity) Reflect.call(context, "getActivity");
        if (activity.isFinishing()
            || activity.isDestroyed()
            || !(boolean) Reflect.call(context, "isRunning")) return;
        if (snapshot && rt.enabled(ConfigProvider.ANIMATION)) {
          try {
            motion =
                new RawLensMotion17(
                    rt,
                    context,
                    layer,
                    oldCameraId,
                    cameraId,
                    () -> {
                      motion = null;
                      Integer nextLens = queuedLens;
                      queuedLens = null;
                      if (nextLens != null && rt.enabled(ConfigProvider.ANIMATION)) {
                        rt.main.post(
                            () -> {
                              try {
                                if (!prepare(engine, nextLens))
                                  switchCamera.invoke(engine, nextLens);
                              } catch (Throwable error) {
                                rt.fail(error);
                              }
                            });
                      }
                    });
            motion.start();
          } catch (Exception unsupported) {
            motion = null;
            ModernHook.log(
                "Expert RAW lens geometry unavailable; retaining native blur: " + unsupported);
          }
        }
        original.set(true);
        try {
          switchCamera.invoke(engine, cameraId);
        } finally {
          original.remove();
        }
        rt.report(
            snapshot
                ? "EXPERT RAW: native preview transition"
                : "EXPERT RAW: snapshot unavailable; native lens switch");
      } catch (Throwable e) {
        if (motion != null) motion.finish();
        rt.fail(e);
      }
    }
  }
}
