package dev.ryuya.galaxycamera;

import android.content.Context;
import android.os.Bundle;
import android.util.AttributeSet;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.util.*;

/** Inserts Samsung's own preferences in Camera Assistant, backed by the shared provider. */
final class AssistantUi17 {
  private final HostRuntime rt;
  private final Map<Object, State> states = new WeakHashMap<>();
  private WeakReference<Object> resumed = new WeakReference<>(null);
  private Class<?> preference, category, changeListener;

  AssistantUi17(HostRuntime runtime) {
    rt = runtime;
  }

  void install() throws Exception {
    ClassLoader l = rt.loader;
    preference = Class.forName("androidx.preference.Preference", false, l);
    category = Class.forName("androidx.preference.PreferenceCategory", false, l);
    changeListener =
        Class.forName("androidx.preference.Preference$OnPreferenceChangeListener", false, l);
    Class<?> fragment = Class.forName("com.samsung.android.app.cameraassistant.a1", false, l);
    for (String method : new String[] {"onResume", "i"})
      ModernHook.findAndHookMethod(
          fragment,
          method,
          new ModernHook.Callback() {
            protected void afterHookedMethod(ModernHook.Param p) {
              if (p.hasThrowable()) return;
              try {
                bind(p.thisObject);
              } catch (Throwable e) {
                rt.fail(e);
              }
            }
          });
    ModernHook.findAndHookMethod(
        fragment,
        "onDestroy",
        new ModernHook.Callback() {
          protected void beforeHookedMethod(ModernHook.Param p) {
            states.remove(p.thisObject);
          }
        });
    rt.onChanged(
        () -> {
          Object current = resumed.get();
          if (current != null)
            try {
              bind(current);
            } catch (Throwable e) {
              rt.fail(e);
            }
        });
    rt.ready();
  }

  private void bind(Object fragment) throws Exception {
    resumed = new WeakReference<>(fragment);
    if (!rt.available()) return;
    Object screen = Reflect.call(fragment, "getPreferenceScreen");
    if (screen == null) return;
    State state = states.get(fragment);
    if (state != null && state.screen == screen) {
      state.refresh();
      return;
    }
    if ((int) Reflect.call(screen, "getPreferenceCount") == 0) return;
    Context host = (Context) Reflect.call(fragment, "requireContext");
    Context strings = host.createPackageContext(BuildConfig.APPLICATION_ID, 0);
    rt.guard.arm();
    Object group = category.getConstructor(Context.class).newInstance(host);
    call(group, "setKey", String.class, "galaxycamera.category");
    call(group, "setTitle", CharSequence.class, strings.getText(R.string.app_name));
    call(group, "setOrder", int.class, 10000);
    call(screen, "addPreference", preference, group);
    State created = new State(screen);
    states.put(fragment, created);
    Class<?> nativeSwitch =
        Class.forName(
            "com.samsung.android.app.cameraassistant.CameraSwitchPreference", false, rt.loader);
    int[] titles = {
      R.string.photo_title,
      R.string.video_title,
      R.string.raw_title,
      R.string.light_title,
      R.string.focus_title,
      R.string.panel_light_title
    };
    int[] summaries = {
      R.string.photo_summary,
      R.string.video_summary,
      R.string.raw_summary,
      R.string.light_summary,
      R.string.focus_summary,
      R.string.panel_light_summary
    };
    for (int i = 0; i < ConfigProvider.KEYS.length; i++) {
      String key = ConfigProvider.KEYS[i];
      Object pref =
          nativeSwitch.getConstructor(Context.class, AttributeSet.class).newInstance(host, null);
      call(pref, "setKey", String.class, "galaxycamera." + key);
      call(pref, "setPersistent", boolean.class, false);
      call(pref, "setTitle", CharSequence.class, strings.getText(titles[i]));
      call(pref, "setSummary", CharSequence.class, strings.getText(summaries[i]));
      Object callback =
          Proxy.newProxyInstance(
              rt.loader,
              new Class<?>[] {changeListener},
              (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class)
                  return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> "Galaxy Camera Tools preference";
                  };
                if (!method.getName().equals("onPreferenceChange")
                    || args == null
                    || args.length != 2
                    || !(args[1] instanceof Boolean value)) return false;
                try {
                  Bundle next = new Bundle();
                  next.putBoolean(key, value);
                  host.getContentResolver().call(ConfigProvider.URI, "set", null, next);
                  return true;
                } catch (RuntimeException e) {
                  rt.report("ASSISTANT: unable to save setting");
                  return false;
                }
              });
      call(pref, "setOnPreferenceChangeListener", changeListener, callback);
      call(group, "addPreference", preference, pref);
      created.preferences.put(key, pref);
    }
    Object hint = preference.getConstructor(Context.class).newInstance(host);
    call(hint, "setKey", String.class, "galaxycamera.apply_hint");
    call(hint, "setSummary", CharSequence.class, strings.getText(R.string.apply_hint));
    call(hint, "setSelectable", boolean.class, false);
    call(group, "addPreference", preference, hint);
    WeakReference<State> weak = new WeakReference<>(created);
    rt.onChanged(
        () -> {
          State current = weak.get();
          if (current != null)
            try {
              current.refresh();
            } catch (Exception e) {
              rt.fail(e);
            }
        });
    created.refresh();
    rt.report("ASSISTANT: native settings connected");
  }

  private static void call(Object target, String name, Class<?> type, Object value)
      throws Exception {
    Reflect.method(target.getClass(), name, type).invoke(target, value);
  }

  private final class State {
    final Object screen;
    final Map<String, Object> preferences = new HashMap<>();

    State(Object root) {
      screen = root;
    }

    void refresh() throws Exception {
      Bundle options = rt.context.getContentResolver().call(ConfigProvider.URI, "get", null, null);
      if (options == null) return;
      for (var entry : preferences.entrySet())
        call(
            entry.getValue(),
            "setChecked",
            boolean.class,
            options.getBoolean(entry.getKey(), false));
    }
  }
}
