package dev.ryuya.galaxycamera;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Grants provider visibility only to the verified, explicitly scoped host UID. */
public final class ConfigurationAccessReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context context, Intent intent) {
    int sender = getSentFromUid();
    if (sender < 0 || sender / 100000 != 0) return;
    String[] packages = context.getPackageManager().getPackagesForUid(sender);
    if (packages == null) return;
    for (String name : packages) {
      boolean host = ConfigProvider.HOSTS.contains(name);
      boolean cameraClient =
          sender >= 10000
              && context.checkPermission(android.Manifest.permission.CAMERA, -1, sender)
                  == android.content.pm.PackageManager.PERMISSION_GRANTED;
      if (host || cameraClient) {
        context.grantUriPermission(
            name,
            ConfigProvider.URI,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
                | (host ? Intent.FLAG_GRANT_WRITE_URI_PERMISSION : 0));
      }
    }
  }
}
