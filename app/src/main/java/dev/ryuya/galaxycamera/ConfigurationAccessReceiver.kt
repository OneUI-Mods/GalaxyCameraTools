package dev.ryuya.galaxycamera

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/* Grants provider visibility only to the verified, explicitly scoped host UID. */
class ConfigurationAccessReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val sender = getSentFromUid()
        if (sender < 0 || sender / 100000 != 0) return
        val packages = context.getPackageManager().getPackagesForUid(sender)
        if (packages == null) return
        for (name in packages) {
            val host = ConfigProvider.HOSTS!!.contains(name)
            val cameraClient: Boolean =
                sender >= 10000 &&
                    (context.checkPermission(Manifest.permission.CAMERA, -1, sender) ==
                        PackageManager.PERMISSION_GRANTED)
            if (host || cameraClient) {
                context.grantUriPermission(
                    name,
                    ConfigProvider.URI,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        (if (host) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0),
                )
            }
        }
    }
}
