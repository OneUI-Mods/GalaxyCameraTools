package dev.ryuya.galaxycamera

import android.Manifest
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.content.pm.PackageManager.NameNotFoundException
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Process
import java.util.Set
import java.util.UUID
import kotlin.jvm.JvmField
import kotlin.math.min
import org.json.JSONException

/* Only our UI and the scoped Camera Assistant may change settings. */
class ConfigProvider : ContentProvider() {
    private val lightRelay: CameraLightRelay = CameraLightRelay()

    override fun onCreate(): Boolean {
        // One-time migration keeps the old photo easing choice, then both switches are independent.
        val prefs = getContext()!!.getSharedPreferences("settings", 0)
        if (!prefs.contains(ConfigProvider.LENS_MOTION))
            prefs
                .edit()
                .putBoolean(ConfigProvider.LENS_MOTION, prefs.getBoolean(ConfigProvider.FPS, false))
                .apply()
        return true
    }

    private fun caller(method: String): String? {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return BuildConfig.APPLICATION_ID
        val name = getCallingPackage()
        if (
            uid / 100000 != 0 ||
                name == null ||
                (!ConfigProvider.HOSTS!!.contains(name) &&
                    !("get" == method && normalCameraClient(uid)))
        )
            throw SecurityException("Unsupported caller")
        try {
            if (getContext()!!.getPackageManager().getApplicationInfo(name, 0).uid != uid)
                throw SecurityException("Caller UID")
        } catch (e: NameNotFoundException) {
            throw SecurityException(e)
        }
        return name
    }

    private fun normalCameraClient(uid: Int): Boolean {
        return uid >= 10000 &&
            uid < 100000 &&
            (getContext()!!.checkPermission(Manifest.permission.CAMERA, -1, uid) ==
                PackageManager.PERMISSION_GRANTED)
    }

    override fun call(method: String, arg: String?, input: Bundle?): Bundle? {
        val who = caller(method)
        val prefs = getContext()!!.getSharedPreferences("settings", 0)
        if ("zoom_curves_publish" == method) {
            if (ConfigProvider.CAMERA != who) throw SecurityException("Camera only")
            try {
                StockZoomCurves.decode(arg)
            } catch (error: JSONException) {
                throw IllegalArgumentException("Invalid zoom curves", error)
            }
            getContext()!!
                .getSharedPreferences("stock_zoom", 0)
                .edit()
                .putString("firmware", Build.FINGERPRINT)
                .putString("curves", arg)
                .apply()
            return Bundle.EMPTY
        }
        if ("zoom_curves_read" == method) {
            if (ConfigProvider.EXPERT != who) throw SecurityException("Expert RAW only")
            val stock = getContext()!!.getSharedPreferences("stock_zoom", 0)
            val result = Bundle()
            if (Build.FINGERPRINT == stock.getString("firmware", ""))
                result.putString("curves", stock.getString("curves", "[]"))
            return result
        }
        if ("light_read" == method)
            return lightRelay.state(prefs.getBoolean(ConfigProvider.PANEL_LIGHT, false))
        if ("light_state" == method) {
            if (ConfigProvider.CAMERA != who || input == null)
                throw SecurityException("Camera only")
            if (lightRelay.update(input))
                getContext()!!.getContentResolver().notifyChange(CameraLightRelay.STATE_URI!!, null)
            return lightRelay.state(prefs.getBoolean(ConfigProvider.PANEL_LIGHT, false))
        }
        if ("light_request" == method) {
            if (ConfigProvider.SYSTEM_UI != who || input == null)
                throw SecurityException("SystemUI only")
            val accepted =
                lightRelay.request(
                    input.getBoolean("enabled"),
                    prefs.getBoolean(ConfigProvider.PANEL_LIGHT, false),
                )
            if (accepted)
                getContext()!!
                    .getContentResolver()
                    .notifyChange(CameraLightRelay.COMMAND_URI!!, null)
            val result = Bundle()
            result.putBoolean("accepted", accepted)
            return result
        }
        if ("set" == method) {
            if (
                (who != BuildConfig.APPLICATION_ID && who != ConfigProvider.ASSISTANT) ||
                    input == null
            )
                throw SecurityException("UI only")
            val edit = prefs.edit()
            for (key in ConfigProvider.KEYS!!) if (input.containsKey(key))
                edit.putBoolean(key, input.getBoolean(key))
            edit.apply()
            getContext()!!.getContentResolver().notifyChange(ConfigProvider.URI!!, null)
        } else if ("reset" == method) {
            if (who != BuildConfig.APPLICATION_ID && who != ConfigProvider.ASSISTANT)
                throw SecurityException("UI only")
            prefs.edit().putString("reset", UUID.randomUUID().toString()).apply()
            getContext()!!.getContentResolver().notifyChange(ConfigProvider.URI!!, null)
        } else if ("status" == method) {
            if (!ConfigProvider.HOSTS!!.contains(who)) throw SecurityException("Host only")
            getContext()!!
                .getSharedPreferences("status", 0)
                .edit()
                .putString(who, if (arg == null) "" else arg.substring(0, min(1200, arg.length)))
                .putLong(who + ".time", System.currentTimeMillis())
                .apply()
            getContext()!!
                .getContentResolver()
                .notifyChange(Uri.withAppendedPath(ConfigProvider.URI, "status"), null)
        } else require("get" == method) { "Unknown operation" }
        val result = Bundle()
        for (key in ConfigProvider.KEYS!!) result.putBoolean(key, prefs.getBoolean(key, false))
        result.putString("reset", prefs.getString("reset", ""))
        val status = getContext()!!.getSharedPreferences("status", 0)
        for (host in ConfigProvider.HOSTS!!) {
            result.putString(host, status.getString(host, ""))
            result.putLong(host + ".time", status.getLong(host + ".time", 0))
        }
        return result
    }

    override fun query(
        u: Uri,
        p: Array<String?>?,
        s: String?,
        a: Array<String?>?,
        o: String?,
    ): Cursor? {
        throw UnsupportedOperationException()
    }

    override fun getType(u: Uri): String? {
        return null
    }

    override fun insert(u: Uri, v: ContentValues?): Uri? {
        throw UnsupportedOperationException()
    }

    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<String?>?): Int {
        throw UnsupportedOperationException()
    }

    override fun delete(u: Uri, s: String?, a: Array<String?>?): Int {
        throw UnsupportedOperationException()
    }

    companion object {
        const val SYSTEM_UI: String = "com.android.systemui"
        const val CAMERA: String = "com.sec.android.app.camera"
        const val ASSISTANT: String = "com.samsung.android.app.cameraassistant"
        const val EXPERT: String = "com.samsung.android.app.galaxyraw"
        const val FPS: String = "photo_preview_60"
        const val VIDEO: String = "video_120"
        const val ANIMATION: String = "expert_lens_animation"
        const val LIGHT: String = "ultra_wide_light"
        const val FOCUS: String = "flash_focus_hold"
        const val PANEL_LIGHT: String = "camera_quick_panel_light"
        const val CAMERA2: String = "camera2_extra_lenses"
        const val LENS_MOTION: String = "camera_lens_animation"
        const val LIGHT_PREVIEW: String = "light_photo_preview"
        @JvmField
        val HOSTS: MutableSet<String?>? =
            Set.of<String?>(
                ConfigProvider.CAMERA,
                ConfigProvider.ASSISTANT,
                ConfigProvider.EXPERT,
                ConfigProvider.SYSTEM_UI,
            )
        @JvmField
        val KEYS: Array<String?>? =
            arrayOf<String?>(
                ConfigProvider.FPS,
                ConfigProvider.VIDEO,
                ConfigProvider.ANIMATION,
                ConfigProvider.LIGHT,
                ConfigProvider.FOCUS,
                ConfigProvider.PANEL_LIGHT,
                ConfigProvider.CAMERA2,
                ConfigProvider.LENS_MOTION,
                ConfigProvider.LIGHT_PREVIEW,
            )
        @JvmField val URI: Uri? = Uri.parse("content://dev.ryuya.galaxycamera.config")
    }
}
