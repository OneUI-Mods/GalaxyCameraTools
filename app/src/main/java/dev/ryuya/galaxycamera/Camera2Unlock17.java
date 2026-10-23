package dev.ryuya.galaxycamera;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraDevice;
import android.os.Parcel;
import android.hardware.camera2.params.OutputConfiguration;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Presents supported physical lenses through Android's native logical-camera output routing. */
final class Camera2Unlock17 {
  private static final String LOGICAL_REAR_CAMERA = "0";
  private static final Set<String> REVIEWED_PHYSICAL_LENSES = Set.of("5", "6", "7");
  private final HostRuntime runtime;
  private final ThreadLocal<String> openingPhysicalLens = new ThreadLocal<>();
  private final ThreadLocal<Integer> nativeRequestDepth = new ThreadLocal<>();
  private final Map<Object, String> openedDevices =
      Collections.synchronizedMap(new WeakHashMap<>());
  private volatile Set<String> availablePhysicalLenses = Set.of();

  Camera2Unlock17(HostRuntime runtime) {
    this.runtime = runtime;
  }

  void install() throws Exception {
    exposePhysicalLensChoices();
    routeLensOpenRequests();
    routeSessionOutputs();
    ModernHook.log("Camera2 physical lens selection installed");
  }

  private void exposePhysicalLensChoices() {
    ModernHook.findAndHookMethod(
        CameraManager.class,
        "getCameraIdList",
        new ModernHook.Callback() {
          protected void afterHookedMethod(ModernHook.Param call) {
            if (call.hasThrowable() || !runtime.enabled(ConfigProvider.CAMERA2)) return;
            try {
              CameraManager manager = (CameraManager) call.thisObject;
              CameraCharacteristics logical = manager.getCameraCharacteristics(LOGICAL_REAR_CAMERA);
              Set<String> supported = new LinkedHashSet<>(logical.getPhysicalCameraIds());
              supported.retainAll(REVIEWED_PHYSICAL_LENSES);
              for (String physical : new ArrayList<>(supported)) {
                Integer facing =
                    manager
                        .getCameraCharacteristics(physical)
                        .get(CameraCharacteristics.LENS_FACING);
                if (facing == null || facing != CameraCharacteristics.LENS_FACING_BACK)
                  supported.remove(physical);
              }
              availablePhysicalLenses = Set.copyOf(supported);
              LinkedHashSet<String> choices =
                  new LinkedHashSet<>(Arrays.asList((String[]) call.getResult()));
              supported.stream().sorted().forEach(choices::add);
              call.setResult(choices.toArray(String[]::new));
            } catch (Throwable error) {
              runtime.fail(error);
            }
          }
        });
  }

  private void routeLensOpenRequests() throws Exception {
    Class<?> device =
        Class.forName("android.hardware.camera2.impl.CameraDeviceImpl", false, runtime.loader);
    ModernHook.hookAllConstructors(
        device,
        new ModernHook.Callback() {
          protected void afterHookedMethod(ModernHook.Param call) {
            String physical = openingPhysicalLens.get();
            if (!call.hasThrowable() && physical != null)
              openedDevices.put(call.thisObject, physical);
          }
        });
    // Keep the service-facing camera ID intact. Only the app's public device ID
    // describes the selected physical lens; the native session still belongs to 0.
    ModernHook.findAndHookMethod(
        device,
        "getId",
        new ModernHook.Callback() {
          protected void afterHookedMethod(ModernHook.Param call) {
            String physical = openedDevices.get(call.thisObject);
            if (!call.hasThrowable() && physical != null && nativeRequestDepth.get() == null)
              call.setResult(physical);
          }
        });
    // Android serializes the logical ID into every request. Keep that ID at 0
    // while native builders are created; public getId() still names the selected lens.
    for (Method method : device.getDeclaredMethods()) {
      if (!Set.of("createCaptureRequest", "createReprocessCaptureRequest", "startStreaming")
          .contains(method.getName())) continue;
      ModernHook.aroundMethod(
          method,
          chain -> {
            if (!openedDevices.containsKey(chain.getThisObject())) return chain.proceed();
            Integer previousDepth = nativeRequestDepth.get();
            nativeRequestDepth.set(previousDepth == null ? 1 : previousDepth + 1);
            try {
              return chain.proceed();
            } finally {
              if (previousDepth == null) nativeRequestDepth.remove();
              else nativeRequestDepth.set(previousDepth);
            }
          });
    }
    for (Method method : CameraManager.class.getDeclaredMethods()) {
      if (!method.getName().equals("openCameraDeviceUserAsync")) continue;
      ModernHook.hookMethod(
          method,
          new ModernHook.Callback() {
            protected void beforeHookedMethod(ModernHook.Param call) {
              if (!runtime.enabled(ConfigProvider.CAMERA2)
                  || !availablePhysicalLenses.contains(call.args[0])) return;
              runtime.guard.arm();
              String selectedPhysicalLens = (String) call.args[0];
              call.setObjectExtra("physical_lens", selectedPhysicalLens);
              openingPhysicalLens.set(selectedPhysicalLens);
              call.args[0] = LOGICAL_REAR_CAMERA;
            }

            protected void afterHookedMethod(ModernHook.Param call) {
              if (call.getObjectExtra("physical_lens") != null) openingPhysicalLens.remove();
            }
          });
    }
  }

  private static OutputConfiguration copyOutput(OutputConfiguration original) {
    Parcel parcel = Parcel.obtain();
    try {
      original.writeToParcel(parcel, 0);
      parcel.setDataPosition(0);
      return OutputConfiguration.CREATOR.createFromParcel(parcel);
    } finally {
      parcel.recycle();
    }
  }

  private void routeSessionOutputs() throws Exception {
    Class<?> device =
        Class.forName("android.hardware.camera2.impl.CameraDeviceImpl", false, runtime.loader);
    for (Method method : device.getDeclaredMethods()) {
      if (!method.getName().equals("createCaptureSessionInternal")) continue;
      ModernHook.hookMethod(
          method,
          new ModernHook.Callback() {
            protected void beforeHookedMethod(ModernHook.Param call) {
              String physical = openedDevices.get(call.thisObject);
              if (physical == null) return;
              try {
                List<?> originals = (List<?>) call.args[1];
                List<OutputConfiguration> outputs = new ArrayList<>(originals.size());
                for (Object original : originals) {
                  OutputConfiguration output = copyOutput((OutputConfiguration) original);
                  output.setPhysicalCameraId(physical);
                  outputs.add(output);
                }
                call.args[1] = outputs;
                ModernHook.log("Camera2 session routed to physical lens " + physical);
              } catch (Throwable error) {
                // Do not silently capture from a different lens if routing fails.
                ((CameraDevice) call.thisObject).close();
                runtime.fail(error);
              }
            }
          });
      ModernHook.deoptimize(method);
    }
  }
}
