package dev.ryuya.galaxycamera

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.View.OnClickListener
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.OnApplyWindowInsetsListener
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsCompat.Type
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textview.MaterialTextView
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import kotlin.jvm.JvmRecord
import kotlin.math.max
import org.json.JSONArray

/* Searchable component index and separate, readable license/attribution pages. */
class LicensesActivity : AppCompatActivity() {
    @JvmRecord
    private data class Library(
        val id: String,
        val title: String,
        val version: String,
        val license: String,
        val asset: String,
        val notice: String,
        val reference: Boolean,
    ) {
        override fun toString(): String =
            "Library[id=$id, title=$title, version=$version, license=$license, asset=$asset, notice=$notice, reference=$reference]"
    }

    private val libraries = ArrayList<LicensesActivity.Library>()
    private lateinit var root: LinearLayout
    private lateinit var toolbar: MaterialToolbar
    private lateinit var list: ListView
    private var query = ""
    private var selected: String? = null
    private var first = 0
    private var top = 0
    private var detail: SettingsTabs? = null
    private var direct = false

    public override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false)
        getWindow()
            .setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        root = LinearLayout(this)
        root.setOrientation(LinearLayout.VERTICAL)
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(
            root,
            OnApplyWindowInsetsListener { v: View, insets: WindowInsetsCompat ->
                val bars = insets.getInsets(Type.systemBars() or Type.displayCutout())
                val ime = insets.getInsets(Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, max(bars.bottom, ime.bottom))
                insets
            },
        )
        ViewCompat.requestApplyInsets(root)
        query = if (state == null) "" else state.getString("license_query", "")
        first = if (state == null) 0 else state.getInt("license_first", 0)
        top = if (state == null) 0 else state.getInt("license_top", 0)
        selected =
            if (state == null) getIntent().getStringExtra("library") else state.getString("library")
        direct = getIntent().hasExtra("library")
        try {
            val items = JSONArray(read("legal/libraries.json"))
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                libraries.add(
                    LicensesActivity.Library(
                        item.getString("id"),
                        item.getString("title"),
                        item.optString("version"),
                        item.getString("license"),
                        item.getString("asset"),
                        item.optString("notice"),
                        item.optBoolean("reference"),
                    )
                )
            }
        } catch (error: Exception) {
            header(getString(R.string.legal_title), null)
            val message = text(getString(R.string.legal_load_error), false)
            root.addView(message)
            return
        }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    back()
                }
            },
        )
        val initial = library(selected)
        if (initial == null) showCatalog() else showDetail(initial, state)
    }

    private fun back() {
        if (selected != null && !direct) {
            selected = null
            showCatalog()
        } else finish()
    }

    private fun header(title: String?, subtitle: String?) {
        root.removeAllViews()
        toolbar = MaterialToolbar(this)
        toolbar.setTitle(title)
        toolbar.setSubtitle(subtitle)
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        toolbar.setNavigationContentDescription(R.string.legal_back)
        toolbar.setNavigationOnClickListener(OnClickListener { v: View? -> back() })
        root.addView(toolbar, android.widget.LinearLayout.LayoutParams(-1, -2))
    }

    private fun showCatalog() {
        detail = null
        header(getString(R.string.legal_title), getString(R.string.legal_catalog_hint))
        val field = TextInputLayout(this)
        field.setHint(getString(R.string.legal_search))
        field.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE)
        val search = TextInputEditText(field.getContext())
        search.setId(R.id.license_search)
        search.setSingleLine(true)
        search.setText(query)
        field.addView(search, android.widget.LinearLayout.LayoutParams(-1, -2))
        val searchSize = android.widget.LinearLayout.LayoutParams(-1, -2)
        searchSize.setMargins(dp(20), dp(12), dp(20), dp(12))
        root.addView(field, searchSize)
        val results = FrameLayout(this)
        list = ListView(this)
        list.setId(R.id.license_list)
        list.setDivider(ColorDrawable(Color.TRANSPARENT))
        list.setDividerHeight(dp(10))
        list.setPadding(dp(20), dp(8), dp(20), dp(20))
        list.setClipToPadding(false)
        results.addView(list, android.widget.FrameLayout.LayoutParams(-1, -1))
        val empty = text(getString(R.string.legal_no_results), false)
        empty.setGravity(Gravity.CENTER)
        results.addView(empty, android.widget.FrameLayout.LayoutParams(-1, -1))
        list.setEmptyView(empty)
        root.addView(results, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
        filter()
        list.post(Runnable { list.setSelectionFromTop(first, top) })
        search.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) {}

                override fun onTextChanged(
                    s: CharSequence,
                    start: Int,
                    before: Int,
                    count: Int,
                ) {
                    query = s.toString()
                    first = 0
                    top = 0
                    filter()
                }

                override fun afterTextChanged(s: Editable?) {}
            }
        )
    }

    private fun filter() {
        val needle = query.trim().lowercase()
        val visible = ArrayList<LicensesActivity.Library>()
        for (item in libraries) if (
            (title(item) + " " + item.id + " " + version(item) + " " + item.license)
                .lowercase()
                .contains(needle)
        )
            visible.add(item)
        list.setAdapter(
            object : BaseAdapter() {
                override fun getCount(): Int {
                    return visible.size
                }

                override fun getItem(p: Int): Any? {
                    return visible.get(p)
                }

                override fun getItemId(p: Int): Long {
                    return p.toLong()
                }

                override fun getView(
                    p: Int,
                    recycled: View,
                    parent: ViewGroup?,
                ): View {
                    val item = visible.get(p)
                    val card = MaterialCardView(this@LicensesActivity)
                    val body = LinearLayout(this@LicensesActivity)
                    body.setOrientation(LinearLayout.VERTICAL)
                    body.setPadding(dp(20), dp(16), dp(20), dp(16))
                    card.addView(body)
                    body.addView(text(title(item), true))
                    val description =
                        text(
                            (if (version(item).isBlank()) "" else version(item) + " · ") +
                                item.license,
                            false,
                        )
                    body.addView(description)
                    if (item.reference)
                        body.addView(text(getString(R.string.legal_reference_only), false))
                    card.setOnClickListener(
                        OnClickListener { v: View? ->
                            rememberList()
                            val ime =
                                getSystemService<InputMethodManager?>(
                                    InputMethodManager::class.java
                                )
                            ime.hideSoftInputFromWindow(root.getWindowToken(), 0)
                            selected = item.id
                            showDetail(item, null)
                        }
                    )
                    return card
                }
            }
        )
    }

    private fun showDetail(
        item: LicensesActivity.Library,
        saved: Bundle?,
    ) {
        header(title(item), version(item))
        val detail = SettingsTabs(this, root, saved)
        this.detail = detail
        val license = detail.page(R.string.legal_license_text)
        val badge = text(item.license, true)
        license.addView(badge)
        if (!item.id.equals("project") && !item.id.equals("notices"))
            license.addView(text(item.id, false))
        val body = text(readOrError(item.asset), false)
        body.setId(R.id.license_text)
        body.setTextIsSelectable(true)
        body.setTextSize(14f)
        body.setLineSpacing(dp(4).toFloat(), 1f)
        body.setPadding(0, dp(16), 0, dp(24))
        license.addView(body)
        if (!item.notice.isEmpty()) {
            val notices = detail.page(R.string.legal_notices)
            val notice =
                text(
                    if (item.notice.startsWith("legal/")) readOrError(item.notice) else item.notice,
                    false,
                )
            notice.setTextIsSelectable(true)
            notice.setTextSize(14f)
            notice.setLineSpacing(dp(4).toFloat(), 1f)
            notices.addView(notice)
        }
        detail.restore()
    }

    private fun text(
        value: String?,
        heading: Boolean,
    ): MaterialTextView {
        val text = MaterialTextView(this)
        text.setText(value)
        text.setTextAppearance(
            if (heading) com.google.android.material.R.style.TextAppearance_Material3_TitleMedium
            else com.google.android.material.R.style.TextAppearance_Material3_BodyMedium
        )
        return text
    }

    private fun title(item: LicensesActivity.Library): String {
        if (item.id.equals("project")) return getString(R.string.app_name)
        if (item.id.equals("notices")) return getString(R.string.legal_distribution_notices)
        return item.title
    }

    private fun version(item: LicensesActivity.Library): String {
        return if (item.id.equals("project")) BuildConfig.VERSION_NAME else item.version
    }

    private fun library(id: String?): LicensesActivity.Library? {
        for (item in libraries) if (item.id == id) return item
        return null
    }

    @Throws(IOException::class)
    private fun read(path: String): String {
        getAssets().open(path).use { stream ->
            return String(stream.readAllBytes(), StandardCharsets.UTF_8)
        }
    }

    private fun readOrError(path: String): String {
        try {
            return read(path)
        } catch (error: IOException) {
            return getString(R.string.legal_load_error)
        }
    }

    private fun rememberList() {
        if (::list.isInitialized) {
            first = list.getFirstVisiblePosition()
            top = if (list.getChildCount() > 0) list.getChildAt(0).getTop() else 0
        }
    }

    override fun onSaveInstanceState(out: Bundle) {
        if (selected == null) rememberList()
        out.putString("license_query", query)
        out.putInt("license_first", first)
        out.putInt("license_top", top)
        out.putString("library", selected)
        detail?.save(out)
        super.onSaveInstanceState(out)
    }

    private fun dp(n: Int): Int {
        return Math.round(n * getResources().getDisplayMetrics().density)
    }
}
