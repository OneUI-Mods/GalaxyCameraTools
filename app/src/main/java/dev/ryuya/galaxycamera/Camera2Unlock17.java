package dev.ryuya.galaxycamera;

import android.hardware.camera2.CameraManager;
import java.lang.reflect.Method;
import java.util.Set;

/** Removes the client-side visibility filter only for reviewed rear camera IDs on BZI9. */
final class Camera2Unlock17 {
  private static final Set<String> REAR_CAMERA_IDS =
      Set.of("20", "21", "23", "24", "52", "54", "56", "58");
  private final HostRuntime runtime;

  Camera2Unlock17(HostRuntime runtime) {
    this.runtime = runtime;
  }

  void install() throws Exception {
    for (String name : new String[] {"isPublicId", "lambda$isPublicId$0"}) {
      Method visibility = Reflect.method(CameraManager.class, name, String.class);
      ModernHook.hookMethod(
          visibility,
          new ModernHook.Callback() {
            protected void afterHookedMethod(ModernHook.Param call) {
              if (!call.hasThrowable()
                  && runtime.enabled(ConfigProvider.CAMERA2)
                  && REAR_CAMERA_IDS.contains(call.args[0])) {
                runtime.guard.arm();
                call.setResult(true);
              }
            }
          });
    }
    // Boot-image callers may have inlined Samsung's filter before module loading.
    for (Method method : CameraManager.class.getDeclaredMethods()) {
      if (method.getName().equals("getCameraCharacteristics")
          || method.getName().equals("getCameraIdList")) ModernHook.deoptimize(method);
    }
    for (String name :
        new String[] {
          "android.hardware.camera2.CameraManager$CameraManagerGlobal$$ExternalSyntheticLambda0",
          "android.hardware.camera2.CameraManager$$ExternalSyntheticLambda0"
        }) {
      Class<?> filter = Class.forName(name, false, runtime.loader);
      for (Method method : filter.getDeclaredMethods()) ModernHook.deoptimize(method);
    }
    ModernHook.log("Camera2 additional rear lens visibility installed");
  }
}
