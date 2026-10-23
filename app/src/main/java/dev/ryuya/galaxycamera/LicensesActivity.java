package dev.ryuya.galaxycamera;

import android.os.Bundle;
import android.text.*;
import android.view.*;
import android.widget.*;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.*;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.*;
import com.google.android.material.textview.MaterialTextView;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Searchable component index and separate, readable license/attribution pages. */
public final class LicensesActivity extends AppCompatActivity {
  private record Library(
      String id,
      String title,
      String version,
      String license,
      String asset,
      String notice,
      boolean reference) {}

  private final List<Library> libraries = new ArrayList<>();
  private LinearLayout root;
  private MaterialToolbar toolbar;
  private ListView list;
  private String query = "", selected;
  private int first, top;
  private SettingsTabs detail;
  private boolean direct;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
    getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    setContentView(root);
    ViewCompat.setOnApplyWindowInsetsListener(
        root,
        (v, insets) -> {
          var bars =
              insets.getInsets(
                  WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
          var ime = insets.getInsets(WindowInsetsCompat.Type.ime());
          v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
          return insets;
        });
    ViewCompat.requestApplyInsets(root);
    query = state == null ? "" : state.getString("license_query", "");
    first = state == null ? 0 : state.getInt("license_first", 0);
    top = state == null ? 0 : state.getInt("license_top", 0);
    selected = state == null ? getIntent().getStringExtra("library") : state.getString("library");
    direct = getIntent().hasExtra("library");
    try {
      JSONArray items = new JSONArray(read("legal/libraries.json"));
      for (int i = 0; i < items.length(); i++) {
        JSONObject item = items.getJSONObject(i);
        libraries.add(
            new Library(
                item.getString("id"),
                item.getString("title"),
                item.optString("version"),
                item.getString("license"),
                item.getString("asset"),
                item.optString("notice"),
                item.optBoolean("reference")));
      }
    } catch (Exception error) {
      header(getString(R.string.legal_title), null);
      var message = text(getString(R.string.legal_load_error), false);
      root.addView(message);
      return;
    }
    getOnBackPressedDispatcher()
        .addCallback(
            this,
            new OnBackPressedCallback(true) {
              @Override
              public void handleOnBackPressed() {
                back();
              }
            });
    Library initial = library(selected);
    if (initial == null) showCatalog();
    else showDetail(initial, state);
  }

  private void back() {
    if (selected != null && !direct) {
      selected = null;
      showCatalog();
    } else finish();
  }

  private void header(String title, String subtitle) {
    root.removeAllViews();
    toolbar = new MaterialToolbar(this);
    toolbar.setTitle(title);
    toolbar.setSubtitle(subtitle);
    toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
    toolbar.setNavigationContentDescription(R.string.legal_back);
    toolbar.setNavigationOnClickListener(v -> back());
    root.addView(toolbar, new LinearLayout.LayoutParams(-1, -2));
  }

  private void showCatalog() {
    detail = null;
    header(getString(R.string.legal_title), getString(R.string.legal_catalog_hint));
    TextInputLayout field = new TextInputLayout(this);
    field.setHint(getString(R.string.legal_search));
    field.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
    TextInputEditText search = new TextInputEditText(field.getContext());
    search.setId(R.id.license_search);
    search.setSingleLine(true);
    search.setText(query);
    field.addView(search, new LinearLayout.LayoutParams(-1, -2));
    LinearLayout.LayoutParams searchSize = new LinearLayout.LayoutParams(-1, -2);
    searchSize.setMargins(dp(20), dp(12), dp(20), dp(12));
    root.addView(field, searchSize);
    FrameLayout results = new FrameLayout(this);
    list = new ListView(this);
    list.setId(R.id.license_list);
    list.setDivider(
        new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
    list.setDividerHeight(dp(10));
    list.setPadding(dp(20), dp(8), dp(20), dp(20));
    list.setClipToPadding(false);
    results.addView(list, new FrameLayout.LayoutParams(-1, -1));
    MaterialTextView empty = text(getString(R.string.legal_no_results), false);
    empty.setGravity(Gravity.CENTER);
    results.addView(empty, new FrameLayout.LayoutParams(-1, -1));
    list.setEmptyView(empty);
    root.addView(results, new LinearLayout.LayoutParams(-1, 0, 1));
    filter();
    list.post(() -> list.setSelectionFromTop(first, top));
    search.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

          public void onTextChanged(CharSequence s, int start, int before, int count) {
            query = s.toString();
            first = 0;
            top = 0;
            filter();
          }

          public void afterTextChanged(Editable s) {}
        });
  }

  private void filter() {
    String needle = query.strip().toLowerCase(Locale.ROOT);
    List<Library> visible = new ArrayList<>();
    for (Library item : libraries)
      if ((title(item) + " " + item.id + " " + version(item) + " " + item.license)
          .toLowerCase(Locale.ROOT)
          .contains(needle)) visible.add(item);
    list.setAdapter(
        new BaseAdapter() {
          public int getCount() {
            return visible.size();
          }

          public Object getItem(int p) {
            return visible.get(p);
          }

          public long getItemId(int p) {
            return p;
          }

          public View getView(int p, View recycled, ViewGroup parent) {
            Library item = visible.get(p);
            MaterialCardView card = new MaterialCardView(LicensesActivity.this);
            LinearLayout body = new LinearLayout(LicensesActivity.this);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setPadding(dp(20), dp(16), dp(20), dp(16));
            card.addView(body);
            body.addView(text(title(item), true));
            MaterialTextView description =
                text((version(item).isBlank() ? "" : version(item) + " · ") + item.license, false);
            body.addView(description);
            if (item.reference) body.addView(text(getString(R.string.legal_reference_only), false));
            card.setOnClickListener(
                v -> {
                  rememberList();
                  var ime = getSystemService(android.view.inputmethod.InputMethodManager.class);
                  ime.hideSoftInputFromWindow(root.getWindowToken(), 0);
                  selected = item.id;
                  showDetail(item, null);
                });
            return card;
          }
        });
  }

  private void showDetail(Library item, Bundle saved) {
    header(title(item), version(item));
    detail = new SettingsTabs(this, root, saved);
    LinearLayout license = detail.page(R.string.legal_license_text);
    MaterialTextView badge = text(item.license, true);
    license.addView(badge);
    if (!item.id.equals("project") && !item.id.equals("notices"))
      license.addView(text(item.id, false));
    MaterialTextView body = text(readOrError(item.asset), false);
    body.setId(R.id.license_text);
    body.setTextIsSelectable(true);
    body.setTextSize(14);
    body.setLineSpacing(dp(4), 1);
    body.setPadding(0, dp(16), 0, dp(24));
    license.addView(body);
    if (!item.notice.isEmpty()) {
      LinearLayout notices = detail.page(R.string.legal_notices);
      MaterialTextView notice =
          text(item.notice.startsWith("legal/") ? readOrError(item.notice) : item.notice, false);
      notice.setTextIsSelectable(true);
      notice.setTextSize(14);
      notice.setLineSpacing(dp(4), 1);
      notices.addView(notice);
    }
    detail.restore();
  }

  private MaterialTextView text(String value, boolean heading) {
    MaterialTextView text = new MaterialTextView(this);
    text.setText(value);
    text.setTextAppearance(
        heading
            ? com.google.android.material.R.style.TextAppearance_Material3_TitleMedium
            : com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
    return text;
  }

  private String title(Library item) {
    if (item.id.equals("project")) return getString(R.string.app_name);
    if (item.id.equals("notices")) return getString(R.string.legal_distribution_notices);
    return item.title;
  }

  private String version(Library item) {
    return item.id.equals("project") ? BuildConfig.VERSION_NAME : item.version;
  }

  private Library library(String id) {
    for (Library item : libraries) if (Objects.equals(item.id, id)) return item;
    return null;
  }

  private String read(String path) throws java.io.IOException {
    try (var stream = getAssets().open(path)) {
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private String readOrError(String path) {
    try {
      return read(path);
    } catch (java.io.IOException error) {
      return getString(R.string.legal_load_error);
    }
  }

  private void rememberList() {
    if (list != null) {
      first = list.getFirstVisiblePosition();
      top = list.getChildCount() > 0 ? list.getChildAt(0).getTop() : 0;
    }
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    if (selected == null) rememberList();
    out.putString("license_query", query);
    out.putInt("license_first", first);
    out.putInt("license_top", top);
    out.putString("library", selected);
    if (detail != null) detail.save(out);
    super.onSaveInstanceState(out);
  }

  private int dp(int n) {
    return Math.round(n * getResources().getDisplayMetrics().density);
  }
}
