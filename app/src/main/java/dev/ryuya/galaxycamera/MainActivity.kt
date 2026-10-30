package dev.ryuya.galaxycamera

import android.content.Intent
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.View.OnClickListener
import android.widget.CompoundButton
import android.widget.CompoundButton.OnCheckedChangeListener
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.OnApplyWindowInsetsListener
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsCompat.Type
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textview.MaterialTextView
import java.util.HashMap
import kotlin.math.round

class MainActivity : AppCompatActivity() {
    private var tabs: SettingsTabs? = null
    private val switches: MutableMap<String?, MaterialSwitch?> = HashMap<String?, MaterialSwitch?>()
    private var status: MaterialTextView? = null
    private var binding: Boolean = false
    private val observer: ContentObserver =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(self: Boolean) {
                refresh()
            }
        }

    public override fun onCreate(saved: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(saved)
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false)
        val root = LinearLayout(this)
        root.setOrientation(LinearLayout.VERTICAL)
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(
            root,
            OnApplyWindowInsetsListener { v: View?, insets: WindowInsetsCompat? ->
                val bars = insets!!.getInsets(Type.systemBars() or Type.displayCutout())
                v!!.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets!!
            },
        )
        val toolbar = MaterialToolbar(this)
        toolbar.setTitle(R.string.app_name)
        root.addView(toolbar, LayoutParams(-1, dp(64)))
        tabs = SettingsTabs(this, root, saved)
        val camera = tabs!!.page(R.string.tab_camera)
        section(camera, R.string.photo_section)
        toggle(camera, ConfigProvider.FPS, R.string.photo_title, R.string.photo_summary)
        toggle(
            camera,
            ConfigProvider.LIGHT_PREVIEW,
            R.string.light_preview_title,
            R.string.light_preview_summary,
        )
        section(camera, R.string.lens_motion_section)
        toggle(
            camera,
            ConfigProvider.LENS_MOTION,
            R.string.lens_motion_title,
            R.string.lens_motion_summary,
        )
        section(camera, R.string.video_section)
        toggle(camera, ConfigProvider.VIDEO, R.string.video_title, R.string.video_summary)
        text(
            camera,
            R.string.video_limits,
            com.google.android.material.R.style.TextAppearance_Material3_BodyMedium,
        )
        section(camera, R.string.light_section)
        toggle(camera, ConfigProvider.LIGHT, R.string.light_title, R.string.light_summary)
        toggle(camera, ConfigProvider.FOCUS, R.string.focus_title, R.string.focus_summary)
        toggle(
            camera,
            ConfigProvider.PANEL_LIGHT,
            R.string.panel_light_title,
            R.string.panel_light_summary,
        )
        text(
            camera,
            R.string.apply_hint,
            com.google.android.material.R.style.TextAppearance_Material3_BodyMedium,
        )
        val raw = tabs!!.page(R.string.tab_raw)
        section(raw, R.string.raw_section)
        toggle(raw, ConfigProvider.ANIMATION, R.string.raw_title, R.string.raw_summary)
        text(
            raw,
            R.string.raw_hint,
            com.google.android.material.R.style.TextAppearance_Material3_BodyMedium,
        )
        val camera2 = tabs!!.page(R.string.tab_camera2)
        toggle(camera2, ConfigProvider.CAMERA2, R.string.camera2_title, R.string.camera2_summary)
        val info = tabs!!.page(R.string.tab_info)
        val requirements = card(info)
        text(
            requirements,
            R.string.setup_title,
            com.google.android.material.R.style.TextAppearance_Material3_TitleMedium,
        )
        text(
            requirements,
            R.string.setup_summary,
            com.google.android.material.R.style.TextAppearance_Material3_BodyMedium,
        )
        text(
            requirements,
            R.string.support_summary,
            com.google.android.material.R.style.TextAppearance_Material3_BodyMedium,
        )
        val state = card(info)
        text(
            state,
            R.string.status_title,
            com.google.android.material.R.style.TextAppearance_Material3_TitleMedium,
        )
        status =
            text(state, 0, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        status!!.setTextIsSelectable(true)
        val reset =
            MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle,
            )
        reset.setText(R.string.reset_guard)
        state.addView(reset)
        reset.setOnClickListener(
            OnClickListener { v: View? ->
                getContentResolver().call(ConfigProvider.URI!!, "reset", null, null)
                refresh()
            }
        )
        text(
            state,
            R.string.guard_summary,
            com.google.android.material.R.style.TextAppearance_Material3_BodySmall,
        )
        val licenses =
            MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle,
            )
        licenses.setText(R.string.legal_title)
        info.addView(licenses)
        licenses.setOnClickListener(
            OnClickListener { v: View? ->
                startActivity(Intent(this, LicensesActivity::class.java))
            }
        )
        text(
            info,
            getString(R.string.version, BuildConfig.VERSION_NAME),
            com.google.android.material.R.style.TextAppearance_Material3_BodySmall,
        )
        tabs!!.restore()
        refresh()
        ViewCompat.requestApplyInsets(root)
    }

    override fun onResume() {
        super.onResume()
        getContentResolver().registerContentObserver(ConfigProvider.URI!!, true, observer)
        refresh()
    }

    override fun onPause() {
        getContentResolver().unregisterContentObserver(observer)
        super.onPause()
    }

    override fun onSaveInstanceState(out: Bundle) {
        tabs!!.save(out!!)
        super.onSaveInstanceState(out!!)
    }

    private fun refresh() {
        if (status == null) return
        val b = getContentResolver().call(ConfigProvider.URI!!, "get", null, null)
        if (b == null) return
        binding = true
        for (entry in switches.entries) entry.value!!.setChecked(b.getBoolean(entry.key, false))
        binding = false
        val s = StringBuilder()
        for (host in
            arrayOf<String?>(
                ConfigProvider.CAMERA,
                ConfigProvider.ASSISTANT,
                ConfigProvider.EXPERT,
            )) {
            val label: String? =
                if (host == ConfigProvider.CAMERA) getString(R.string.tab_camera)
                else if (host == ConfigProvider.ASSISTANT) "Camera Assistant" else "Expert RAW"
            s.append(label).append("\n")
            val value = b.getString(host, "")
            s.append(if (value.isEmpty()) getString(R.string.not_loaded) else value).append("\n\n")
        }
        status!!.setText(s.toString().trim { it <= ' ' })
    }

    private fun toggle(page: LinearLayout, key: String?, title: Int, summary: Int) {
        val body = card(page)
        val row = LinearLayout(this)
        row.setGravity(Gravity.CENTER_VERTICAL)
        val heading = MaterialTextView(this)
        heading.setTextAppearance(
            com.google.android.material.R.style.TextAppearance_Material3_TitleMedium
        )
        heading.setText(title)
        row.addView(heading, LayoutParams(0, -2, 1f))
        heading.setPadding(0, 0, dp(12), 0)
        val toggle = MaterialSwitch(this)
        toggle.setContentDescription(getText(title))
        row.addView(toggle)
        body.addView(row)
        text(body, summary, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        switches.put(key, toggle)
        toggle.setOnCheckedChangeListener(
            OnCheckedChangeListener { v: CompoundButton?, on: Boolean ->
                if (binding) return@OnCheckedChangeListener
                val update = Bundle()
                update.putBoolean(key, on)
                getContentResolver().call(ConfigProvider.URI!!, "set", null, update)
            }
        )
        body.setOnClickListener(
            OnClickListener { v: View? -> toggle.setChecked(!toggle.isChecked()) }
        )
    }

    private fun section(page: LinearLayout?, title: Int) {
        text(page!!, title, com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
    }

    private fun card(page: LinearLayout): LinearLayout {
        val card = MaterialCardView(this)
        card.setCardElevation(0f)
        card.setRadius(dp(24).toFloat())
        card.setStrokeWidth(0)
        card.setCardBackgroundColor(
            MaterialColors.getColor(
                this,
                com.google.android.material.R.attr.colorSurfaceContainerLow,
                0,
            )
        )
        val lp = LayoutParams(-1, -2)
        lp.bottomMargin = dp(16)
        page.addView(card, lp)
        val body = LinearLayout(this)
        body.setOrientation(LinearLayout.VERTICAL)
        body.setPadding(dp(20), dp(16), dp(20), dp(16))
        card.addView(body)
        return body
    }

    private fun text(parent: LinearLayout, id: Int, style: Int): MaterialTextView {
        return text(parent, if (id == 0) "" else getString(id), style)
    }

    private fun text(parent: LinearLayout, value: String?, style: Int): MaterialTextView {
        val view = MaterialTextView(this)
        view.setTextAppearance(style)
        view.setText(value)
        val lp = LayoutParams(-1, -2)
        lp.topMargin = dp(8)
        lp.bottomMargin = dp(12)
        parent.addView(view, lp)
        return view
    }

    private fun dp(value: Int): Int {
        return Math.round(value * getResources().getDisplayMetrics().density)
    }
}
