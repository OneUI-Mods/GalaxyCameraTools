package dev.ryuya.galaxycamera

import android.content.Context
import android.os.Bundle
import android.util.AttributeSet
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.HashMap
import java.util.WeakHashMap
import kotlin.jvm.JvmField
import kotlin.jvm.JvmStatic

/* Inserts Samsung's own preferences in Camera Assistant, backed by the shared provider. */
class AssistantUi17(runtime: HostRuntime) {
    private val rt: HostRuntime
    private val states: MutableMap<Any?, AssistantUi17.State?> =
        WeakHashMap<Any?, AssistantUi17.State?>()
    private var resumed: WeakReference<Any?> = WeakReference<Any?>(null)
    private var preference: Class<*>? = null
    private var category: Class<*>? = null
    private var changeListener: Class<*>? = null

    init {
        rt = runtime
    }

    @Throws(Exception::class)
    fun install() {
        val l = rt.loader
        preference = Class.forName("androidx.preference.Preference", false, l)
        category = Class.forName("androidx.preference.PreferenceCategory", false, l)
        changeListener =
            Class.forName("androidx.preference.Preference\$OnPreferenceChangeListener", false, l)
        val fragment = Class.forName("com.samsung.android.app.cameraassistant.a1", false, l)
        for (method in arrayOf<String>("onResume", "i")) ModernHook.findAndHookMethod(
            fragment,
            method,
            object : ModernHook.Callback() {
                override fun afterHookedMethod(p: ModernHook.Param) {
                    if (p.hasThrowable()) return
                    try {
                        bind(p.thisObject)
                    } catch (e: Throwable) {
                        rt.fail(e)
                    }
                }
            },
        )
        ModernHook.findAndHookMethod(
            fragment,
            "onDestroy",
            object : ModernHook.Callback() {
                override fun beforeHookedMethod(p: ModernHook.Param) {
                    states.remove(p.thisObject)
                }
            },
        )
        rt.onChanged(
            Runnable {
                val current = resumed.get()
                if (current != null)
                    try {
                        bind(current)
                    } catch (e: Throwable) {
                        rt.fail(e)
                    }
            }
        )
        rt.ready()
    }

    @Throws(Exception::class)
    private fun bind(fragment: Any?) {
        resumed = WeakReference<Any?>(fragment)
        if (!rt.available()) return
        val screen = Reflect.call(fragment!!, "getPreferenceScreen")
        if (screen == null) return
        val state = states.get(fragment)
        if (state != null && state.screen === screen) {
            state.refresh()
            return
        }
        if (Reflect.call(screen, "getPreferenceCount") as Int == 0) return
        val host: Context? = Reflect.call(fragment!!, "requireContext") as Context?
        val strings = host!!.createPackageContext(BuildConfig.APPLICATION_ID, 0)
        rt.guard.arm()
        val group = category!!.getConstructor(Context::class.java).newInstance(host)
        AssistantUi17.call(group, "setKey", String::class.java, "galaxycamera.category")
        AssistantUi17.call(
            group,
            "setTitle",
            CharSequence::class.java,
            strings.getText(R.string.app_name),
        )
        AssistantUi17.call(group, "setOrder", Int::class.javaPrimitiveType, 10000)
        AssistantUi17.call(screen, "addPreference", preference, group)
        val created = State(screen)
        states.put(fragment, created)
        val nativeSwitch =
            Class.forName(
                "com.samsung.android.app.cameraassistant.CameraSwitchPreference",
                false,
                rt.loader,
            )
        val titles =
            intArrayOf(
                R.string.photo_title,
                R.string.video_title,
                R.string.raw_title,
                R.string.light_title,
                R.string.focus_title,
                R.string.panel_light_title,
                R.string.camera2_title,
                R.string.lens_motion_title,
                R.string.light_preview_title,
            )
        val summaries =
            intArrayOf(
                R.string.photo_summary,
                R.string.video_summary,
                R.string.raw_summary,
                R.string.light_summary,
                R.string.focus_summary,
                R.string.panel_light_summary,
                R.string.camera2_summary,
                R.string.lens_motion_summary,
                R.string.light_preview_summary,
            )
        for (i in ConfigProvider.KEYS!!.indices) {
            val key: String? = ConfigProvider.KEYS[i]
            val pref =
                nativeSwitch
                    .getConstructor(Context::class.java, AttributeSet::class.java)
                    .newInstance(host, null)
            AssistantUi17.call(pref, "setKey", String::class.java, "galaxycamera." + key)
            AssistantUi17.call(pref, "setPersistent", Boolean::class.javaPrimitiveType, false)
            var title = strings.getText(titles[i])
            if (ConfigProvider.ANIMATION == key)
                title = strings.getText(R.string.tab_raw).toString() + ": " + title
            AssistantUi17.call(pref, "setTitle", CharSequence::class.java, title)
            AssistantUi17.call(
                pref,
                "setSummary",
                CharSequence::class.java,
                strings.getText(summaries[i]),
            )
            val callback =
                Proxy.newProxyInstance(
                    rt.loader,
                    arrayOf<Class<*>?>(changeListener),
                    InvocationHandler { proxy: Any?, method: Method?, args: Array<Any?>? ->
                        if (method!!.getDeclaringClass() == Any::class.java)
                            return@InvocationHandler when (method!!.getName()) {
                                "equals" -> proxy === args!![0]
                                "hashCode" -> System.identityHashCode(proxy)
                                else -> "Galaxy Camera Tools preference"
                            }
                        val patternPrefix0 =
                            method!!.getName() != "onPreferenceChange" ||
                                args == null ||
                                args.size != 2
                        val value = if (!patternPrefix0) args[1] as? Boolean else null
                        if (patternPrefix0 || value == null) return@InvocationHandler false
                        try {
                            val next = Bundle()
                            next.putBoolean(key, value)
                            host!!
                                .getContentResolver()
                                .call(ConfigProvider.URI!!, "set", null, next)
                            return@InvocationHandler true
                        } catch (e: RuntimeException) {
                            rt.report("ASSISTANT: unable to save setting")
                            return@InvocationHandler false
                        }
                    },
                )
            AssistantUi17.call(pref, "setOnPreferenceChangeListener", changeListener, callback)
            AssistantUi17.call(group, "addPreference", preference, pref)
            created.preferences.put(key, pref)
        }
        val hint = preference!!.getConstructor(Context::class.java).newInstance(host)
        AssistantUi17.call(hint, "setKey", String::class.java, "galaxycamera.apply_hint")
        AssistantUi17.call(
            hint,
            "setSummary",
            CharSequence::class.java,
            strings.getText(R.string.apply_hint),
        )
        AssistantUi17.call(hint, "setSelectable", Boolean::class.javaPrimitiveType, false)
        AssistantUi17.call(group, "addPreference", preference, hint)
        val weak = WeakReference<AssistantUi17.State?>(created)
        rt.onChanged(
            Runnable {
                val current = weak.get()
                if (current != null)
                    try {
                        current.refresh()
                    } catch (e: Exception) {
                        rt.fail(e)
                    }
            }
        )
        created.refresh()
        rt.report("ASSISTANT: native settings connected")
    }

    private inner class State(root: Any) {
        @JvmField val screen: Any
        @JvmField val preferences: MutableMap<String?, Any?> = HashMap<String?, Any?>()

        init {
            screen = root
        }

        @Throws(Exception::class)
        fun refresh() {
            val options =
                rt.context.getContentResolver().call(ConfigProvider.URI!!, "get", null, null)
            if (options == null) return
            for (entry in preferences.entries) AssistantUi17.call(
                entry.value!!,
                "setChecked",
                Boolean::class.javaPrimitiveType,
                options.getBoolean(entry.key, false),
            )
        }
    }

    companion object {
        @JvmStatic
        @Throws(Exception::class)
        private fun call(target: Any, name: String?, type: Class<*>?, value: Any?) {
            Reflect.method(target.javaClass, name, type)!!.invoke(target, value)
        }
    }
}
