package dev.ryuya.galaxycamera;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import java.util.Set;

/** Only our UI and the scoped Camera Assistant may change settings. */
public final class ConfigProvider extends ContentProvider {
  static final String CAMERA = "com.sec.android.app.camera";
  static final String ASSISTANT = "com.samsung.android.app.cameraassistant";
  static final String EXPERT = "com.samsung.android.app.galaxyraw";
  static final String FPS = "photo_preview_60",
      VIDEO = "video_120",
      ANIMATION = "expert_lens_animation",
      LIGHT = "ultra_wide_light";
  static final Set<String> HOSTS = Set.of(CAMERA, ASSISTANT, EXPERT);
  static final String[] KEYS = {FPS, VIDEO, ANIMATION, LIGHT};
  static final Uri URI = Uri.parse("content://dev.ryuya.galaxycamera.config");

  public boolean onCreate() {
    return true;
  }

  private String caller() {
    int uid = Binder.getCallingUid();
    if (uid == android.os.Process.myUid()) return BuildConfig.APPLICATION_ID;
    String name = getCallingPackage();
    if (uid / 100000 != 0 || name == null || !HOSTS.contains(name))
      throw new SecurityException("Unsupported caller");
    try {
      if (getContext().getPackageManager().getApplicationInfo(name, 0).uid != uid)
        throw new SecurityException("Caller UID");
    } catch (android.content.pm.PackageManager.NameNotFoundException e) {
      throw new SecurityException(e);
    }
    return name;
  }

  public Bundle call(String method, String arg, Bundle input) {
    String who = caller();
    var prefs = getContext().getSharedPreferences("settings", 0);
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
