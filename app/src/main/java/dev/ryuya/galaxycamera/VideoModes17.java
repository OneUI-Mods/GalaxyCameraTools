package dev.ryuya.galaxycamera;

import java.lang.reflect.*;
import java.util.*;

/** Enables existing Samsung 120 fps pipelines in normal video, without inventing sensor modes. */
final class VideoModes17 {
  private final HostRuntime rt;
  private final CameraHooks17 camera;
  private Class<?> resolution, commands, setting, features;
  private Object uhd, uhdCommand, backSetting;

  VideoModes17(HostRuntime runtime, CameraHooks17 hooks) {
    rt = runtime;
    camera = hooks;
  }

  void install() throws Exception {
    ClassLoader l = rt.loader;
    resolution = Class.forName("l9.a2", false, l);
    commands = Class.forName("l9.j0", false, l);
    setting = Class.forName("l9.f0", false, l);
    features = Class.forName("te.f0", false, l);
    Class<?> util = Class.forName("te.c0", false, l), commandMap = Class.forName("d8.r", false, l);
    uhd = Reflect.value(resolution, "RESOLUTION_3840X2160_120FPS");
    uhdCommand = Reflect.value(commands, "BACK_PRO_CAMCORDER_RESOLUTION_UHD_120");
    backSetting = Reflect.value(setting, "BACK_CAMCORDER_RESOLUTION");
    Class<?> videoMaker =
        Class.forName("com.samsung.android.camera.core2.maker.VideoMaker", false, l);
    Class<?> proMaker =
        Class.forName("com.samsung.android.camera.core2.maker.ProVideoMaker", false, l);
    ModernHook.findAndHookMethod(
        Class.forName("com.samsung.android.camera.core2.maker.MakerFactory", false, l),
        "create",
        Class.class,
        Class.forName(
            "com.samsung.android.camera.core2.maker.MakerFactory$Configuration", false, l),
        new ModernHook.Callback() {
          protected void beforeHookedMethod(ModernHook.Param p) {
            if (p.args[0] != videoMaker || !rt.enabled(ConfigProvider.VIDEO) || camera.mode() != 1)
              return;
            try {
              Object selected =
                  Reflect.method(resolution, "j", int.class)
                      .invoke(null, camera.backVideoResolution());
              if (supported(selected)) {
                rt.guard.arm();
                p.args[0] = proMaker;
                rt.report("VIDEO: native high-frame-rate maker selected");
              }
            } catch (Throwable e) {
              rt.fail(e);
            }
          }
        });
    ModernHook.findAndHookMethod(
        Class.forName("com.samsung.android.camera.core2.maker.VideoMakerBase", false, l),
        "startRecordRepeating",
        new ModernHook.Callback() {
          protected void beforeHookedMethod(ModernHook.Param p) {
            try {
              Object raw = Reflect.get(p.thisObject, "mRecordRequestBuilderMap");
              if (!(raw instanceof Map<?, ?> map)) return;
              StringBuilder details = new StringBuilder(p.thisObject.getClass().getSimpleName());
              for (Object builder : map.values()) {
                android.hardware.camera2.CaptureRequest request =
                    ((android.hardware.camera2.CaptureRequest.Builder) builder).build();
                for (android.hardware.camera2.CaptureRequest.Key<?> key : request.getKeys()) {
                  String name = key.getName();
                  if (name.contains("aeTarget")
                      || name.contains("recording")
                      || name.contains("captureIntent")
                      || name.contains("shootingMode")
                      || name.equals("android.control.mode"))
                    details.append("; ").append(name).append('=').append(request.get(key));
                }
              }
              ModernHook.log("RECORD CONFIG: " + details);
            } catch (Throwable e) {
              ModernHook.log("Record diagnostic unavailable: " + e);
            }
          }
        });
    // The normal-video presenter unconditionally adds a JPEG snapshot stream.
    // Clear only that stream after its native preparation at 120 fps; returning
    // null from the size utility would break the presenter's own calculations.
    ModernHook.findAndHookMethod(
        Class.forName("re.e", false, l),
        "Y0",
        Class.forName("l9.x", false, l),
        Class.forName("u8.e", false, l),
        Class.forName("z8.l0", false, l),
        new ModernHook.Callback() {
          protected void afterHookedMethod(ModernHook.Param p) {
            if (p.hasThrowable() || !rt.enabled(ConfigProvider.VIDEO) || camera.mode() != 1) return;
            try {
              Object selected =
                  Reflect.method(resolution, "j", int.class)
                      .invoke(null, camera.backVideoResolution());
              if (supported(selected)) {
                rt.guard.arm();
                Reflect.field(p.args[2].getClass(), "o").set(p.args[2], null);
              }
            } catch (Throwable e) {
              rt.fail(e);
            }
          }
        });
    for (String method : new String[] {"d1", "Z0"}) {
      ModernHook.findAndHookMethod(
          util,
          method,
          String.class,
          resolution,
          new ModernHook.Callback() {
            protected void afterHookedMethod(ModernHook.Param p) {
              if (p.hasThrowable()
                  || !rt.enabled(ConfigProvider.VIDEO)
                  || !"video".equals(p.args[0])) return;
              try {
                if (supported(p.args[1])) {
                  rt.guard.arm();
                  p.setResult(true);
                }
              } catch (Throwable e) {
                rt.fail(e);
              }
            }
          });
    }
    ModernHook.findAndHookMethod(
        util,
        "k0",
        new ModernHook.Callback() {
          protected void afterHookedMethod(ModernHook.Param p) {
            if (p.hasThrowable() || !rt.enabled(ConfigProvider.VIDEO)) return;
            try {
              List<Object> values = new ArrayList<>(Arrays.asList((Object[]) p.getResult()));
              for (String name :
                  new String[] {"RESOLUTION_3840X2160_120FPS", "RESOLUTION_1920X1080_120FPS"}) {
                Object value = Reflect.value(resolution, name);
                if (supported(value) && !values.contains(value)) values.add(value);
              }
              values.sort(
                  Comparator.comparingInt(
                      v -> {
                        try {
                          return (int) Reflect.call(v, "e");
                        } catch (Exception e) {
                          throw new IllegalStateException(e);
                        }
                      }));
              Object array = Array.newInstance(resolution, values.size());
              for (int i = 0; i < values.size(); i++) Array.set(array, i, values.get(i));
              rt.guard.arm();
              p.setResult(array);
            } catch (Throwable e) {
              rt.fail(e);
            }
          }
        });
    ModernHook.findAndHookMethod(
        commandMap,
        "e",
        commands,
        new ModernHook.Callback() {
          protected void afterHookedMethod(ModernHook.Param p) {
            if (p.hasThrowable()
                || !rt.enabled(ConfigProvider.VIDEO)
                || !"BACK_CAMCORDER_RESOLUTION_MENU".equals(p.args[0].toString())) return;
            try {
              if (!supported(uhd)) return;
              ArrayList<Object> next = new ArrayList<>((List<?>) p.getResult());
              if (!next.contains(uhdCommand)) next.add(uhdCommand);
              p.setResult(next);
            } catch (Throwable e) {
              rt.fail(e);
            }
          }
        });
    ModernHook.findAndHookMethod(
        commandMap,
        "c",
        commands,
        new ModernHook.Callback() {
          protected void afterHookedMethod(ModernHook.Param p) {
            if (p.args[0] == uhdCommand && camera.mode() == 1) p.setResult(backSetting);
          }
        });
    // A previously saved UHD120 setting remains displayable after the switch is OFF.
    // This mapping does not add the option to the disabled menu or bypass validation.
    ModernHook.findAndHookMethod(
        commandMap,
        "b",
        int.class,
        setting,
        new ModernHook.Callback() {
          protected void beforeHookedMethod(ModernHook.Param p) {
            if ((int) p.args[0] == 3 && p.args[1] == backSetting) p.setResult(uhdCommand);
          }
        });
    Class<?> ratio = Class.forName("g9.p", false, l), mapping = Class.forName("g9.q", false, l);
    ModernHook.findAndHookMethod(
        mapping,
        "g",
        ratio,
        int.class,
        boolean.class,
        int.class,
        new ModernHook.Callback() {
          protected void afterHookedMethod(ModernHook.Param p) {
            if (camera.mode() == 1
                && "RATIO_16X9_UHD_120FPS".equals(p.args[0].toString())
                && !(boolean) p.args[2]) p.setResult(uhdCommand);
          }
        });
    rt.onChanged(
        () -> {
          try {
            // Normal-video caches are separate from photo / Pro / slow-motion lists.
            for (String[] contract : new String[][] {{"a", "s"}, {"g", "y"}}) {
              synchronized (Reflect.get(util, contract[0])) {
                Reflect.field(util, contract[1]).set(null, null);
              }
            }
          } catch (Throwable e) {
            rt.fail(e);
          }
        });
  }

  private boolean supported(Object value) throws Exception {
    // These two choices have complete normal-mode command mappings. Other
    // native Pro aspect ratios must not enter the normal menu without one.
    if (!Set.of("RESOLUTION_3840X2160_120FPS", "RESOLUTION_1920X1080_120FPS")
        .contains(value.toString())) return false;
    Object raw = Reflect.method(features, "a", resolution).invoke(null, value);
    if (!(raw instanceof Map<?, ?> map) || !Boolean.parseBoolean(String.valueOf(map.get("value"))))
      return false;
    String modes = String.valueOf(map.get("supported-mode"));
    return Arrays.asList(modes.split("\\s*,\\s*")).contains("pro_video")
        || Arrays.asList(modes.split("\\s*,\\s*")).contains("video");
  }
}
