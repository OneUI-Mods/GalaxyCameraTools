package dev.ryuya.galaxycamera;

/** Enables Samsung's existing ultra-wide light path without changing saved camera preferences. */
final class UltraWideLight17 {
  private final HostRuntime rt;

  UltraWideLight17(HostRuntime runtime) {
    rt = runtime;
  }

  void install() throws Exception {
    ClassLoader loader = rt.loader;
    Class<?> key = Class.forName("l9.f0", false, loader);
    Object wideLight = Reflect.value(key, "FLASH_IN_WIDE_LENS");
    ModernHook.findAndHookMethod(
        Class.forName("vc.j", false, loader),
        "d",
        key,
        new ModernHook.Callback() {
          protected void afterHookedMethod(ModernHook.Param p) {
            if (!p.hasThrowable() && p.args[0] == wideLight && rt.enabled(ConfigProvider.LIGHT)) {
              try {
                rt.guard.arm();
                p.setResult(1);
              } catch (Throwable error) {
                rt.fail(error);
              }
            }
          }
        });
    Class<?> feature = Class.forName("e5.d", false, loader);
    Object supported = Reflect.value(feature, "SUPPORT_FLASH_IN_WIDE_LENS");
    ModernHook.findAndHookMethod(
        Class.forName("e5.g", false, loader),
        "o",
        feature,
        new ModernHook.Callback() {
          protected void afterHookedMethod(ModernHook.Param p) {
            if (!p.hasThrowable() && p.args[0] == supported && rt.enabled(ConfigProvider.LIGHT)) {
              try {
                rt.guard.arm();
                p.setResult(true);
              } catch (Throwable error) {
                rt.fail(error);
              }
            }
          }
        });
  }
}
