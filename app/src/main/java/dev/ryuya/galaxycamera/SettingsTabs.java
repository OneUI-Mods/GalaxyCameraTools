package dev.ryuya.galaxycamera;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.core.widget.NestedScrollView;
import com.google.android.material.tabs.TabLayout;
import java.util.*;

/** Fixed navigation, with independent scrolling and saved positions for every page. */
final class SettingsTabs {
  private final Context context;
  final TabLayout tabs;
  private final FrameLayout content;
  private final List<NestedScrollView> pages = new ArrayList<>();
  private final Bundle saved;
  private final List<Integer> pendingScrolls = new ArrayList<>();

  SettingsTabs(Context context, LinearLayout root, Bundle saved) {
    this.context = context;
    this.saved = saved;
    tabs = new TabLayout(context);
    tabs.setId(R.id.settings_tabs);
    tabs.setTabMode(TabLayout.MODE_AUTO);
    tabs.setTabIndicatorFullWidth(false);
    root.addView(tabs, new LinearLayout.LayoutParams(-1, -2));
    content = new FrameLayout(context);
    root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
    tabs.addOnTabSelectedListener(
        new TabLayout.OnTabSelectedListener() {
          public void onTabSelected(TabLayout.Tab tab) {
            show(tab.getPosition());
          }

          public void onTabUnselected(TabLayout.Tab tab) {}

          public void onTabReselected(TabLayout.Tab tab) {}
        });
  }

  LinearLayout page(int title) {
    int index = pages.size();
    NestedScrollView scroll = new NestedScrollView(context);
    scroll.setFillViewport(true);
    LinearLayout body = new LinearLayout(context);
    body.setOrientation(LinearLayout.VERTICAL);
    int padding = Math.round(20 * context.getResources().getDisplayMetrics().density);
    body.setPadding(padding, padding, padding, padding);
    scroll.addView(body);
    pages.add(scroll);
    int restoreY = saved == null ? 0 : saved.getInt("page_scroll_" + index, 0);
    pendingScrolls.add(restoreY > 0 ? restoreY : null);
    content.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
    scroll.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
    tabs.addTab(tabs.newTab().setText(title));
    if (restoreY > 0) {
      scroll
          .getViewTreeObserver()
          .addOnGlobalLayoutListener(
              new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                public void onGlobalLayout() {
                  if (scroll.getVisibility() != View.VISIBLE || scroll.getHeight() == 0) return;
                  scroll.scrollTo(0, restoreY);
                  pendingScrolls.set(index, null);
                  scroll.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                }
              });
    }
    return body;
  }

  void restore() {
    int selected = saved == null ? 0 : saved.getInt("selected_tab", 0);
    TabLayout.Tab tab = tabs.getTabAt(Math.max(0, Math.min(selected, pages.size() - 1)));
    if (tab != null) tab.select();
  }

  void save(Bundle out) {
    out.putInt("selected_tab", Math.max(0, tabs.getSelectedTabPosition()));
    for (int i = 0; i < pages.size(); i++)
      out.putInt(
          "page_scroll_" + i,
          pendingScrolls.get(i) == null ? pages.get(i).getScrollY() : pendingScrolls.get(i));
  }

  private void show(int index) {
    for (int i = 0; i < pages.size(); i++)
      pages.get(i).setVisibility(i == index ? View.VISIBLE : View.GONE);
  }
}
