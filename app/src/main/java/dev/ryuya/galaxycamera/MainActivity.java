package dev.ryuya.galaxycamera;

import android.content.*;
import android.database.ContentObserver;
import android.os.*;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.*;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textview.MaterialTextView;
import java.util.*;

public final class MainActivity extends AppCompatActivity {
  private SettingsTabs tabs;
  private final Map<String, MaterialSwitch> switches = new HashMap<>();
  private MaterialTextView status;
  private boolean binding;
  private final ContentObserver observer =
      new ContentObserver(new Handler(Looper.getMainLooper())) {
        public void onChange(boolean self) {
          refresh();
        }
      };

  public void onCreate(Bundle saved) {
    DynamicColors.applyToActivityIfAvailable(this);
    super.onCreate(saved);
    WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    setContentView(root);
    ViewCompat.setOnApplyWindowInsetsListener(
        root,
        (v, insets) -> {
          var bars =
              insets.getInsets(
                  WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
          v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
          return insets;
        });
    MaterialToolbar toolbar = new MaterialToolbar(this);
    toolbar.setTitle(R.string.app_name);
    root.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(64)));
    tabs = new SettingsTabs(this, root, saved);
    LinearLayout camera = tabs.page(R.string.tab_camera);
    section(camera, R.string.photo_section);
    toggle(camera, ConfigProvider.FPS, R.string.photo_title, R.string.photo_summary);
    section(camera, R.string.video_section);
    toggle(camera, ConfigProvider.VIDEO, R.string.video_title, R.string.video_summary);
    text(
        camera,
        R.string.video_limits,
        com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
    section(camera, R.string.light_section);
    toggle(camera, ConfigProvider.LIGHT, R.string.light_title, R.string.light_summary);
    toggle(camera, ConfigProvider.FOCUS, R.string.focus_title, R.string.focus_summary);
    toggle(
        camera,
        ConfigProvider.PANEL_LIGHT,
        R.string.panel_light_title,
        R.string.panel_light_summary);
    text(
        camera,
        R.string.apply_hint,
        com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
    LinearLayout raw = tabs.page(R.string.tab_raw);
    section(raw, R.string.raw_section);
    toggle(raw, ConfigProvider.ANIMATION, R.string.raw_title, R.string.raw_summary);
    text(
        raw,
        R.string.raw_hint,
        com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
    LinearLayout camera2 = tabs.page(R.string.tab_camera2);
    toggle(camera2, ConfigProvider.CAMERA2, R.string.camera2_title, R.string.camera2_summary);
    LinearLayout info = tabs.page(R.string.tab_info);
    LinearLayout requirements = card(info);
    text(
        requirements,
        R.string.setup_title,
        com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
    text(
        requirements,
        R.string.setup_summary,
        com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
    text(
        requirements,
        R.string.support_summary,
        com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
    LinearLayout state = card(info);
    text(
        state,
        R.string.status_title,
        com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
    status =
        text(state, 0, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
    status.setTextIsSelectable(true);
    MaterialButton reset =
        new MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
    reset.setText(R.string.reset_guard);
    state.addView(reset);
    reset.setOnClickListener(
        v -> {
          getContentResolver().call(ConfigProvider.URI, "reset", null, null);
          refresh();
        });
    text(
        state,
        R.string.guard_summary,
        com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
    MaterialButton licenses =
        new MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
    licenses.setText(R.string.legal_title);
    info.addView(licenses);
    licenses.setOnClickListener(v -> startActivity(new Intent(this, LicensesActivity.class)));
    text(
        info,
        getString(R.string.version, BuildConfig.VERSION_NAME),
        com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
    tabs.restore();
    refresh();
    ViewCompat.requestApplyInsets(root);
  }

  protected void onResume() {
    super.onResume();
    getContentResolver().registerContentObserver(ConfigProvider.URI, true, observer);
    refresh();
  }

  protected void onPause() {
    getContentResolver().unregisterContentObserver(observer);
    super.onPause();
  }

  protected void onSaveInstanceState(Bundle out) {
    tabs.save(out);
    super.onSaveInstanceState(out);
  }

  private void refresh() {
    if (status == null) return;
    Bundle b = getContentResolver().call(ConfigProvider.URI, "get", null, null);
    if (b == null) return;
    binding = true;
    for (var entry : switches.entrySet())
      entry.getValue().setChecked(b.getBoolean(entry.getKey(), false));
    binding = false;
    StringBuilder s = new StringBuilder();
    for (String host :
        new String[] {ConfigProvider.CAMERA, ConfigProvider.ASSISTANT, ConfigProvider.EXPERT}) {
      String label =
          host.equals(ConfigProvider.CAMERA)
              ? getString(R.string.tab_camera)
              : host.equals(ConfigProvider.ASSISTANT) ? "Camera Assistant" : "Expert RAW";
      s.append(label).append("\n");
      String value = b.getString(host, "");
      s.append(value.isEmpty() ? getString(R.string.not_loaded) : value).append("\n\n");
    }
    status.setText(s.toString().trim());
  }

  private void toggle(LinearLayout page, String key, int title, int summary) {
    LinearLayout body = card(page);
    LinearLayout row = new LinearLayout(this);
    row.setGravity(android.view.Gravity.CENTER_VERTICAL);
    MaterialTextView heading = new MaterialTextView(this);
    heading.setTextAppearance(
        com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
    heading.setText(title);
    row.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
    heading.setPadding(0, 0, dp(12), 0);
    MaterialSwitch toggle = new MaterialSwitch(this);
    toggle.setContentDescription(getText(title));
    row.addView(toggle);
    body.addView(row);
    text(body, summary, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
    switches.put(key, toggle);
    toggle.setOnCheckedChangeListener(
        (v, on) -> {
          if (binding) return;
          Bundle update = new Bundle();
          update.putBoolean(key, on);
          getContentResolver().call(ConfigProvider.URI, "set", null, update);
        });
    body.setOnClickListener(v -> toggle.setChecked(!toggle.isChecked()));
  }

  private void section(LinearLayout page, int title) {
    text(page, title, com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
  }

  private LinearLayout card(LinearLayout page) {
    MaterialCardView card = new MaterialCardView(this);
    card.setCardElevation(0);
    card.setRadius(dp(24));
    card.setStrokeWidth(0);
    card.setCardBackgroundColor(
        com.google.android.material.color.MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorSurfaceContainerLow, 0));
    var lp = new LinearLayout.LayoutParams(-1, -2);
    lp.bottomMargin = dp(16);
    page.addView(card, lp);
    LinearLayout body = new LinearLayout(this);
    body.setOrientation(LinearLayout.VERTICAL);
    body.setPadding(dp(20), dp(16), dp(20), dp(16));
    card.addView(body);
    return body;
  }

  private MaterialTextView text(LinearLayout parent, int id, int style) {
    return text(parent, id == 0 ? "" : getString(id), style);
  }

  private MaterialTextView text(LinearLayout parent, String value, int style) {
    MaterialTextView view = new MaterialTextView(this);
    view.setTextAppearance(style);
    view.setText(value);
    var lp = new LinearLayout.LayoutParams(-1, -2);
    lp.topMargin = dp(8);
    lp.bottomMargin = dp(12);
    parent.addView(view, lp);
    return view;
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }
}
