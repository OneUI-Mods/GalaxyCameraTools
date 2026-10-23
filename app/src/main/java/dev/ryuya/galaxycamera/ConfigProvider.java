package dev.ryuya.galaxycamera;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import java.util.Set;

/** Only our UI and the scoped Camera Assistant may change settings. */
public final class ConfigProvider extends ContentProvider {
  static final String SYSTEM_UI = "com.android.systemui";
  private final CameraLightRelay lightRelay = new CameraLightRelay();
  static final String CAMERA = "com.sec.android.app.camera";
  static final String ASSISTANT = "com.samsung.android.app.cameraassistant";
  static final String EXPERT = "com.samsung.android.app.galaxyraw";
  static final String FPS = "photo_preview_60",
      VIDEO = "video_120",
      ANIMATION = "expert_lens_animation",
      LIGHT = "ultra_wide_light",
      FOCUS = "flash_focus_hold",
      PANEL_LIGHT = "camera_quick_panel_light",
      CAMERA2 = "camera2_extra_lenses",
      LENS_MOTION = "camera_lens_animation";
  static final Set<String> HOSTS = Set.of(CAMERA, ASSISTANT, EXPERT, SYSTEM_UI);
  static final String[] KEYS = {
    FPS, VIDEO, ANIMATION, LIGHT, FOCUS, PANEL_LIGHT, CAMERA2, LENS_MOTION
  };
  static final Uri URI = Uri.parse("content://dev.ryuya.galaxycamera.config");

  public boolean onCreate() {
    // One-time migration keeps the old photo easing choice, then both switches are independent.
    var prefs = getContext().getSharedPreferences("settings", 0);
    if (!prefs.contains(LENS_MOTION))
      prefs.edit().putBoolean(LENS_MOTION, prefs.getBoolean(FPS, false)).apply();
    return true;
  }

  private String caller(String method) {
    int uid = Binder.getCallingUid();
    if (uid == android.os.Process.myUid()) return BuildConfig.APPLICATION_ID;
    String name = getCallingPackage();
    if (uid / 100000 != 0
        || name == null
        || (!HOSTS.contains(name) && !("get".equals(method) && normalCameraClient(uid))))
      throw new SecurityException("Unsupported caller");
    try {
      if (getContext().getPackageManager().getApplicationInfo(name, 0).uid != uid)
        throw new SecurityException("Caller UID");
    } catch (android.content.pm.PackageManager.NameNotFoundException e) {
      throw new SecurityException(e);
    }
    return name;
  }

  private boolean normalCameraClient(int uid) {
    return uid >= 10000
        && uid < 100000
        && getContext().checkPermission(android.Manifest.permission.CAMERA, -1, uid)
            == android.content.pm.PackageManager.PERMISSION_GRANTED;
  }

  public Bundle call(String method, String arg, Bundle input) {
    String who = caller(method);
    var prefs = getContext().getSharedPreferences("settings", 0);
    if ("zoom_curves_publish".equals(method)) {
      if (!CAMERA.equals(who)) throw new SecurityException("Camera only");
      try {
        StockZoomCurves.decode(arg);
      } catch (org.json.JSONException error) {
        throw new IllegalArgumentException("Invalid zoom curves", error);
      }
      getContext()
          .getSharedPreferences("stock_zoom", 0)
          .edit()
          .putString("firmware", Build.FINGERPRINT)
          .putString("curves", arg)
          .apply();
      return Bundle.EMPTY;
    }
    if ("zoom_curves_read".equals(method)) {
      if (!EXPERT.equals(who)) throw new SecurityException("Expert RAW only");
      var stock = getContext().getSharedPreferences("stock_zoom", 0);
      Bundle result = new Bundle();
      if (Build.FINGERPRINT.equals(stock.getString("firmware", "")))
        result.putString("curves", stock.getString("curves", "[]"));
      return result;
    }
    if ("light_read".equals(method)) return lightRelay.state(prefs.getBoolean(PANEL_LIGHT, false));
    if ("light_state".equals(method)) {
      if (!CAMERA.equals(who) || input == null) throw new SecurityException("Camera only");
      if (lightRelay.update(input))
        getContext().getContentResolver().notifyChange(CameraLightRelay.STATE_URI, null);
      return lightRelay.state(prefs.getBoolean(PANEL_LIGHT, false));
    }
    if ("light_request".equals(method)) {
      if (!SYSTEM_UI.equals(who) || input == null) throw new SecurityException("SystemUI only");
      boolean accepted =
          lightRelay.request(input.getBoolean("enabled"), prefs.getBoolean(PANEL_LIGHT, false));
      if (accepted)
        getContext().getContentResolver().notifyChange(CameraLightRelay.COMMAND_URI, null);
      Bundle result = new Bundle();
      result.putBoolean("accepted", accepted);
      return result;
    }
    if ("set".equals(method)) {
      if ((!who.equals(BuildConfig.APPLICATION_ID) && !who.equals(ASSISTANT)) || input == null)
        throw new SecurityException("UI only");
      var edit = prefs.edit();
      for (String key : KEYS)
        if (input.containsKey(key)) edit.putBoolean(key, input.getBoolean(key));
      edit.apply();
      getContext().getContentResolver().notifyChange(URI, null);
    } else if ("reset".equals(method)) {
      if (!who.equals(BuildConfig.APPLICATION_ID) && !who.equals(ASSISTANT))
        throw new SecurityException("UI only");
      prefs.edit().putString("reset", java.util.UUID.randomUUID().toString()).apply();
      getContext().getContentResolver().notifyChange(URI, null);
    } else if ("status".equals(method)) {
      if (!HOSTS.contains(who)) throw new SecurityException("Host only");
      getContext()
          .getSharedPreferences("status", 0)
          .edit()
          .putString(who, arg == null ? "" : arg.substring(0, Math.min(1200, arg.length())))
          .putLong(who + ".time", System.currentTimeMillis())
          .apply();
      getContext().getContentResolver().notifyChange(Uri.withAppendedPath(URI, "status"), null);
    } else if (!"get".equals(method)) throw new IllegalArgumentException("Unknown operation");
    Bundle result = new Bundle();
    for (String key : KEYS) result.putBoolean(key, prefs.getBoolean(key, false));
    result.putString("reset", prefs.getString("reset", ""));
    var status = getContext().getSharedPreferences("status", 0);
    for (String host : HOSTS) {
      result.putString(host, status.getString(host, ""));
      result.putLong(host + ".time", status.getLong(host + ".time", 0));
    }
    return result;
  }

  public Cursor query(Uri u, String[] p, String s, String[] a, String o) {
    throw new UnsupportedOperationException();
  }

  public String getType(Uri u) {
    return null;
  }

  public Uri insert(Uri u, ContentValues v) {
    throw new UnsupportedOperationException();
  }

  public int update(Uri u, ContentValues v, String s, String[] a) {
    throw new UnsupportedOperationException();
  }

  public int delete(Uri u, String s, String[] a) {
    throw new UnsupportedOperationException();
  }
}
