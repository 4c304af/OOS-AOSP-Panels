package dev.oos.nativepanels;

import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import java.lang.reflect.Method;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.graphics.Insets;

/** Geometry of the selected QS pane and the stock header that shares its shade window. */
final class SplitShadeChrome {
  static boolean landscape(View frame) {
    return frame.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
  }

  private static int qsId;
  static View qsSlot(View frame) {
    if (qsId == 0) qsId = frame.getResources().getIdentifier("qs_frame", "id", Protocol.TARGET);
    int id = qsId;
    for (View v = frame; v != null; v = v.getParent() instanceof View p ? p : null)
      if (id != 0 && v.getId() == id) return v;
    return frame;
  }

  static boolean split(View frame, boolean reported) {
    if (!landscape(frame)) return false;
    View root = frame.getRootView();
    if (root.getWidth() <= root.getHeight()) return false;
    return reported || narrow(frame.getWidth(), root.getWidth()) || narrow(qsSlot(frame).getWidth(), root.getWidth());
  }

  static boolean narrow(int pane, int window) {
    return pane > 0 && pane <= window * .7f;
  }

  // The fragment is inside the notification window. Use layout coordinates so a pull, fling
  // or temporary parent transform cannot alter safe-area padding during the next traversal.
  static int layoutCoordinate(View child, boolean horizontal) {
    int sum = 0;
    for (View v = child; v.getParent() instanceof View p; v = p)
      sum += horizontal ? v.getLeft() : v.getTop();
    return sum;
  }

  private static int statusHeightId, panelId;
  static Insets safeInsets(WindowInsets window) {
    return window == null ? Insets.NONE : window.getInsetsIgnoringVisibility(
        WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars() | WindowInsets.Type.displayCutout());
  }

  static int barHeight(View frame, WindowInsets window) {
    if (statusHeightId == 0) statusHeightId = frame.getResources().getIdentifier("status_bar_height", "dimen", "android");
    int height = statusHeightId == 0 ? 0 : frame.getResources().getDimensionPixelSize(statusHeightId);
    if (window == null) return height;
    height = Math.max(height, window.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top);
    android.view.DisplayCutout cutout = window.getDisplayCutout();
    if (cutout == null) return height;
    if (cutout.getSafeInsetTop() == 0) return height;
    Rect top = cutout.getBoundingRectTop();
    return Math.max(height, Math.max(cutout.getSafeInsetTop(), top.isEmpty() ? 0 : top.bottom + top.top));
  }

  static int bottomInset(WindowInsets window) {
    if (window == null) return 0;
    int nav = window.getInsets(WindowInsets.Type.navigationBars()).bottom;
    return window.getDisplayCutout() == null ? nav : Math.max(nav, window.getDisplayCutout().getSafeInsetBottom());
  }

  // These margins are owned by the native ConstraintSet. Only trim existing extra space,
  // and preserve any subsequent native update when releasing the pane on rotation or close.
  static final class PaneMargins {
    private View child;
    private ViewGroup.MarginLayoutParams adjusted;
    private int baseTop, baseBottom, appliedTop, appliedBottom;
    void fit(View view, int top, int bottom) {
      if (child != view) restore();
      if (!(view.getParent() instanceof ViewGroup parent) ||
          !(view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams params)) return;
      if (adjusted != params || params.topMargin != appliedTop) baseTop = params.topMargin;
      if (adjusted != params || params.bottomMargin != appliedBottom) baseBottom = params.bottomMargin;
      adjusted = params;
      child = view;
      int start = layoutCoordinate(parent, false);
      appliedTop = Math.min(baseTop, Math.max(0, top - start - parent.getPaddingTop()));
      appliedBottom = Math.min(baseBottom, Math.max(0, start + parent.getHeight() - parent.getPaddingBottom() - bottom));
      if (appliedTop != params.topMargin || appliedBottom != params.bottomMargin) {
        params.topMargin = appliedTop;
        params.bottomMargin = appliedBottom;
        view.setLayoutParams(params);
      }
    }
    void restore() {
      if (child != null && child.getLayoutParams() == adjusted) {
        int top = adjusted.topMargin == appliedTop ? baseTop : adjusted.topMargin;
        int bottom = adjusted.bottomMargin == appliedBottom ? baseBottom : adjusted.bottomMargin;
        if (top != adjusted.topMargin || bottom != adjusted.bottomMargin) {
          adjusted.topMargin = top;
          adjusted.bottomMargin = bottom;
          child.setLayoutParams(adjusted);
        }
      }
      child = null;
      adjusted = null;
    }
  }

  // Move the single live row, including the active icon group and privacy chip.
  // The Compose AndroidView continues owning its own root and can be recreated independently.
  static final class SharedHeader {
    private FrameLayout panel;
    private final PaneMargins margins = new PaneMargins();
    int height, reserve;
    boolean placed() { return panel != null; }

    void place(View frame, View row, FrameLayout embedded, int padding) {
      if (panel == null) {
        if (embedded == null || row.getParent() != embedded) return;
        if (panelId == 0) panelId = frame.getResources().getIdentifier("notification_panel", "id", Protocol.TARGET);
        for (View v = frame; panelId != 0 && v != null; v = v.getParent() instanceof View ancestor ? ancestor : null) {
          if (v.getId() == panelId && v instanceof FrameLayout full && !narrow(full.getWidth(), frame.getRootView().getWidth()) && full.getWidth() > 0) {
            panel = full;
            break;
          }
        }
        if (panel == null) return;
        embedded.removeView(row);
        panel.addView(row, new FrameLayout.LayoutParams(-1, 0, Gravity.TOP));
      }
      WindowInsets window = frame.getRootWindowInsets();
      height = barHeight(frame, window);
      Insets safe = safeInsets(window);
      // WindowRootView can inset the whole notification_panel on a side-cutout device.
      int panelLeft = layoutCoordinate(panel, true);
      int panelRightSpace = Math.max(0, frame.getRootView().getWidth() - panelLeft - panel.getWidth());
      int left = Math.max(0, safe.left - panelLeft - panel.getPaddingLeft()) + padding;
      int right = Math.max(0, safe.right - panelRightSpace - panel.getPaddingRight()) + padding;
      if (row.getPaddingLeft() != left || row.getPaddingRight() != right || row.getPaddingTop() != 0)
        row.setPadding(left, 0, right, 0);
      FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) row.getLayoutParams();
      int offset = layoutCoordinate(panel, false) + panel.getPaddingTop();
      if (lp.height != height || lp.topMargin != -offset) {
        lp.height = height;
        lp.topMargin = -offset;
        row.setLayoutParams(lp);
      }
      View slot = qsSlot(frame);
      if (qsId != 0 && slot.getId() == qsId) margins.fit(slot, height, frame.getRootView().getHeight() - bottomInset(window));
      reserve = Math.max(0, height - layoutCoordinate(frame, false) - frame.getPaddingTop());
    }

    void restore(View row, FrameLayout embedded) {
      if (panel == null) return;
      panel.removeView(row);
      margins.restore();
      panel = null;
      height = reserve = 0;
      row.setAlpha(1f);
      row.setLayoutParams(new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
      if (embedded != null && row.getParent() == null) embedded.addView(row);
    }
  }

  private View header, icons, battery, stockPrivacyGroup;
  private boolean hidPrivacyGroup;
  private boolean hidIcons, hidBattery;
  private long retryAfter;

  private static View named(View parent, String name) {
    int id = parent.getResources().getIdentifier(name, "id", Protocol.TARGET);
    return id == 0 ? null : parent.findViewById(id);
  }

  void showNativeHeader(View frame, boolean enabled) {
    if (!enabled || (header != null && (!header.isAttachedToWindow() || header.getRootView() != frame.getRootView()))) restore();
    if (!enabled) return;
    if (header == null || (icons == null && battery == null)) {
      long now = SystemClock.uptimeMillis();
      if (now < retryAfter) return;
      retryAfter = now + 350;
      header = named(frame.getRootView(), "split_shade_status_bar");
      if (header == null) return;
      battery = named(header, "batteryRemainingIcon");
      icons = battery == null ? named(header, "shade_header_system_icons") : null;
      if (icons == null) icons = named(header, "statusIcons");
    }
    hidIcons = suppress(icons, hidIcons);
    hidBattery = suppress(battery, hidBattery);
  }

  private static boolean suppress(View view, boolean owned) {
    if (view == null || view.getVisibility() == View.GONE) return false;
    if (view.getVisibility() != View.VISIBLE) return owned;
    view.setVisibility(View.INVISIBLE);
    return true;
  }

  // Keep the stock privacy indicator available unless its replacement is actually visible.
  void syncPrivacy(boolean replacementVisible) {
    if (replacementVisible && header != null) {
      if (stockPrivacyGroup == null) stockPrivacyGroup = named(header, "icons");
      hidPrivacyGroup = suppress(stockPrivacyGroup, hidPrivacyGroup);
    } else if (hidPrivacyGroup) {
      if (stockPrivacyGroup != null && stockPrivacyGroup.getVisibility() == View.INVISIBLE)
        stockPrivacyGroup.setVisibility(View.VISIBLE);
      hidPrivacyGroup = false;
    }
  }

  void restore() {
    if (hidIcons && icons != null && icons.getVisibility() == View.INVISIBLE) icons.setVisibility(View.VISIBLE);
    if (hidBattery && battery != null && battery.getVisibility() == View.INVISIBLE) battery.setVisibility(View.VISIBLE);
    syncPrivacy(false);
    stockPrivacyGroup = icons = battery = header = null;
    hidIcons = hidBattery = false;
    retryAfter = 0;
  }

  boolean suppressed() { return hidIcons || hidBattery; }

  /** A sibling immediately behind the live stack, in the same compositing layer. */
  static final class Surface extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect bounds = new Rect();
    private final int[] origin = new int[2], stackAt = new int[2], qsAt = new int[2];
    private final int gutterId, radiusId, sharedId;
    private final View list;
    private final Rect visibleList = new Rect(), localClip = new Rect();
    private final Path listPath = new Path();
    private final Method readPadding, updatePadding;
    private int targetPadding = -1, gutterPx;
    private float systemPadding;
    private boolean writingPadding;
    private final PaneMargins margins = new PaneMargins();
    private int radius, draws;

    Surface(ViewGroup parent, View stack) {
      super(parent.getContext());
      list = stack;
      Method read = null, update = null;
      try {
        read = stack.getClass().getMethod("getTopPadding");
        update = stack.getClass().getMethod("updateTopPadding", float.class, boolean.class);
      } catch (NoSuchMethodException absent) { /* Keep the host's row placement on other ROMs. */ }
      readPadding = read;
      updatePadding = update;
      sharedId = getResources().getIdentifier("shared_notification_container", "id", Protocol.TARGET);
      setId(View.generateViewId());
      setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      setVisibility(View.INVISIBLE);
      gutterId = getResources().getIdentifier("notification_side_paddings_split", "dimen", Protocol.TARGET);
      radiusId = getResources().getIdentifier("notification_corner_radius", "dimen", Protocol.TARGET);
      parent.addView(this, parent.indexOfChild(stack), new ViewGroup.LayoutParams(-1, -1));
    }

    private int dimension(int id, int dp) {
      return id != 0 ? getResources().getDimensionPixelSize(id)
          : Math.round(dp * getResources().getDisplayMetrics().density);
    }

    // Remember every native request, including requests made while this column is open.
    float onSystemPadding(float value) {
      if (writingPadding || targetPadding < 0) return value;
      systemPadding = value;
      return targetPadding;
    }

    private void writePadding(float value) throws ReflectiveOperationException {
      writingPadding = true;
      try { updatePadding.invoke(list, value, false); }
      finally { writingPadding = false; }
    }

    private void setRowTop(int target) throws ReflectiveOperationException {
      if (updatePadding == null || target == targetPadding) return;
      int current = (int)readPadding.invoke(list);
      if (targetPadding < 0) systemPadding = current;
      targetPadding = target;
      if (current != target) writePadding(target);
    }

    private void restoreList() {
      if (targetPadding >= 0) {
        int previous = targetPadding;
        targetPadding = -1;
        try {
          if ((int)readPadding.invoke(list) == previous && (int)systemPadding != previous) writePadding(systemPadding);
        } catch (ReflectiveOperationException ignored) { /* The next native update owns the padding. */ }
      }
      margins.restore();
    }

    void hideColumn() {
      restoreList();
      if (getVisibility() != View.INVISIBLE) {
        setVisibility(View.INVISIBLE);
        list.invalidate();
      }
    }

    boolean ownsList(View view) { return view == list; }

    boolean forList(View view) { return list == view && isShown() && !localClip.isEmpty(); }

    void clipRows(Canvas canvas) { canvas.clipPath(listPath); }

    boolean containsTouch(float x, float y) {
      x += list.getScrollX(); y += list.getScrollY();
      if (x < localClip.left || x >= localClip.right || y < localClip.top || y >= localClip.bottom) return false;
      float r = Math.min(radius, Math.min(localClip.width(), localClip.height()) / 2f);
      float dx = x - Math.max(localClip.left + r, Math.min(x, localClip.right - r));
      float dy = y - Math.max(localClip.top + r, Math.min(y, localClip.bottom - r));
      return dx * dx + dy * dy <= r * r;
    }

    int scrollEnd(int nativeBottomPadding) {
      return localClip.bottom - list.getScrollY() - Math.max(0, gutterPx - Math.max(0, nativeBottomPadding));
    }

    boolean place(View stack, View frame, int color, int alpha) throws ReflectiveOperationException {
      if (getWidth() == 0 || getHeight() == 0 || !stack.isShown() || !stack.getLocalVisibleRect(visibleList)) {
        hideColumn();
        return false;
      }
      getLocationInWindow(origin);
      stack.getLocationInWindow(stackAt);
      View slot = qsSlot(frame), root = frame.getRootView();
      slot.getLocationInWindow(qsAt);
      int full = root.getWidth(), left = stackAt[0], right = left + stack.getWidth();
      boolean rtl = frame.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
      boolean narrowStack = narrow(stack.getWidth(), full);
      boolean qsIsLeft = narrowStack ? left + stack.getWidth() / 2 > full / 2
          : narrow(slot.getWidth(), full) ? qsAt[0] + slot.getWidth() / 2 < full / 2 : !rtl;
      int divider = full / 2;
      int gutter = dimension(gutterId, 16);
      WindowInsets window = frame.getRootWindowInsets();
      Insets safe = safeInsets(window);
      left = qsIsLeft ? divider : safe.left;
      right = qsIsLeft ? full - safe.right : divider;
      int header = barHeight(frame, window);
      int end = root.getHeight() - bottomInset(window);
      if (header >= end) { hideColumn(); return false; }
      if (sharedId != 0 && stack.getParent() instanceof View parent && parent.getId() == sharedId)
        margins.fit(stack, header, end);
      int top = Math.max(header, stackAt[1] + visibleList.top);
      int bottom = Math.min(end, stackAt[1] + visibleList.bottom);
      gutterPx = gutter;
      setRowTop(Math.max(0, header + gutter - layoutCoordinate(stack, false)));
      left = Math.max(0, left - origin[0]); right = Math.min(getWidth(), right - origin[0]);
      top = Math.max(0, top - origin[1]); bottom = Math.min(getHeight(), bottom - origin[1]);
      if (left >= right || top >= bottom) { hideColumn(); return false; }
      int fill = (color & 0xffffff) | (alpha << 24);
      int round = dimension(radiusId, 16);
      int dx = origin[0] - stackAt[0] + list.getScrollX(), dy = origin[1] - stackAt[1] + list.getScrollY();
      if (localClip.left != left + dx || localClip.top != top + dy || localClip.right != right + dx
          || localClip.bottom != bottom + dy || radius != round || getVisibility() != View.VISIBLE) {
        localClip.set(left + dx, top + dy, right + dx, bottom + dy);
        listPath.rewind();
        listPath.addRoundRect(localClip.left, localClip.top, localClip.right, localClip.bottom, round, round, Path.Direction.CW);
        list.invalidate();
      }
      if (bounds.left != left || bounds.right != right || bounds.top != top || bounds.bottom != bottom
          || radius != round || paint.getColor() != fill) {
        bounds.set(left, top, right, bottom); radius = round; paint.setColor(fill); invalidate();
      }
      setVisibility(View.VISIBLE);
      return true;
    }

    @Override protected void onDraw(Canvas canvas) {
      canvas.drawRoundRect(bounds.left, bounds.top, bounds.right, bounds.bottom, radius, radius, paint);
      draws++;
    }
    String status() { return bounds.toShortString() + " rowTopPadding=" + targetPadding + " draws=" + draws; }
  }
}
