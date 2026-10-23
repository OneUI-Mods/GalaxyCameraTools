package dev.ryuya.galaxycamera;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import io.github.libxposed.api.XposedModule;

public final class HookEntry extends XposedModule {
  private String process;

  public void onModuleLoaded(ModuleLoadedParam p) {
    ModernHook.attach(this);
    process = p.getProcessName();
    ModernHook.log("Module loaded: " + process);
  }

  public void onPackageReady(PackageReadyParam p) {
    String name = p.getPackageName();
    ModernHook.log("Package ready: " + name + "; first=" + p.isFirstPackage());
    if (!p.isFirstPackage() || !name.equals(process) || !ConfigProvider.HOSTS.contains(name))
      return;
    ModernHook.findAndHookMethod(
        Application.class,
        "attach",
        Context.class,
        new ModernHook.Callback() {
          boolean attached;

          protected void afterHookedMethod(ModernHook.Param call) {
            if (attached || call.hasThrowable()) return;
            Context c = (Context) call.args[0];
            HostRuntime runtime = null;
            if (!name.equals(c.getPackageName())) return;
            attached = true;
            try {
              HostRuntime rt = new HostRuntime(c, p.getClassLoader());
              runtime = rt;
              if (Build.VERSION.SDK_INT != 37
                  || !"SM-S948Q".equals(Build.MODEL)
                  || !"CP2A.260605.016.S948QOPU1BZI9".equals(Build.DISPLAY)) {
                rt.report("UNSUPPORTED: device / firmware");
                return;
              }
              if (!rt.guard.allowed()) {
                rt.ready();
                return;
              }
              String version = c.getPackageManager().getPackageInfo(name, 0).versionName;
              if (name.equals(ConfigProvider.CAMERA) && "17.0.00.43".equals(version))
                new CameraHooks17(rt).install();
              else if (name.equals(ConfigProvider.ASSISTANT) && "5.0.00.7".equals(version))
                new AssistantUi17(rt).install();
              else if (name.equals(ConfigProvider.EXPERT) && "6.0.02.8".equals(version))
                new ExpertRawHooks17(rt).install();
              else if (name.equals(ConfigProvider.SYSTEM_UI)) new QuickPanelLight17(rt).install();
              else rt.report("UNSUPPORTED: application version " + version);
            } catch (Throwable e) {
              if (runtime != null) runtime.fail(e);
              else ModernHook.log("Host feature unavailable: " + e);
            }
          }
        });
  }
}
