package dev.oos.nativepanels;

import android.content.*;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.lang.reflect.Method;

/** Replace the artwork in the measured OOS battery slot, including during status bar transitions. */
final class HomeStatusBattery extends FrameLayout implements ViewTreeObserver.OnPreDrawListener {
  private final PanelRuntime r;
  private final LinearLayout original;
  private final boolean keyguard;
  private final View bar;
  private ViewGroup icons;
  private String adjacentSlot = "unknown";
  private EndPadding endPadding;
  private boolean pendingGap;
  private final Runnable applyGap = this::applyPostedGap;

  private void applyPostedGap() {
    pendingGap = false;
    applyIconGap(ready && mobileAdjacent);
  }
  private final Runnable checkIcons = this::refreshIcons;
  private final OnLayoutChangeListener iconLayout = (v,l,t,r,b,ol,ot,or,ob) -> checkIcons.run();

  private void refreshIcons() { if (icons != null) r.homeBattery.updateNeighbour(icons, this); }
  private final View icon, oldStyle;
  private final ChargingIndicator chargingIndicator;
  private final ShadeBattery battery;
  private final BatteryAccessibility accessibility;
  private final BatteryFrameSize frameSize;
  private final Space attributionSpace;
  private int extraWidth;
  private final Method currentIcon, tintColor, contentOffset;
  private final RectF bounds = new RectF();
  private StatusBarBatteryTint statusTint;
  private int lastForeground;
  private ContentResolver settings;
  private boolean receiverRegistered, failed, ready, mobileAdjacent;

  private final ContentObserver observer;
  private final BroadcastReceiver users = new BroadcastReceiver() {
    @Override public void onReceive(Context context, Intent intent) { observeUser(); }
  };

  static boolean hasOwners(PanelRuntime r, LinearLayout original) throws ReflectiveOperationException {
    for (String owner : new String[]{"androidx.lifecycle.ViewTreeLifecycleOwner", "androidx.savedstate.ViewTreeSavedStateRegistryOwner"}) {
      if (r.type(owner).getMethod("get", View.class).invoke(null, original) == null) return false;
    }
    return true;
  }

  static void install(PanelRuntime r, LinearLayout original, View bar, boolean keyguard) throws Throwable {
    if (!(original.getParent() instanceof LinearLayout parent)) throw new IllegalStateException("Unexpected battery parent");
    HomeStatusBattery host = new HomeStatusBattery(r, original, bar, keyguard);
    int index = parent.indexOfChild(original);
    ViewGroup.LayoutParams params = original.getLayoutParams();
    parent.removeViewAt(index);
    try {
      host.addView(original, 0, new FrameLayout.LayoutParams(-2, -1));
      parent.addView(host, index, params);
    } catch (Throwable e) {
      if (host.getParent() == parent) parent.removeView(host);
      if (original.getParent() == host) host.removeView(original);
      parent.addView(original, index, params);
      throw e;
    }
  }

  private HomeStatusBattery(PanelRuntime r, LinearLayout original, View bar, boolean keyguard) throws Throwable {
    super(original.getContext());
    this.r = r;
    this.bar = bar;
    this.original = original;
    this.keyguard = keyguard;
    Context ctx = getContext();
    icon = original.requireViewById(ctx.getResources().getIdentifier("battery_icon_view", "id", Protocol.TARGET));
    oldStyle = original.requireViewById(ctx.getResources().getIdentifier("battery_content", "id", Protocol.TARGET));
    int chargeId = ctx.getResources().getIdentifier("battery_charge_icon", "id", Protocol.TARGET);
    chargingIndicator = new ChargingIndicator(chargeId == 0 ? null : original.findViewById(chargeId));
    currentIcon = Reflect.method(original.getClass(), "getBatteryIcon");
    tintColor = Reflect.method(currentIcon.getReturnType(), "getTintColor");
    contentOffset = Reflect.method(original.getClass(), "getTransXForCoord");
    frameSize = BatteryFrameSize.read(r);
    attributionSpace = new Space(ctx);
    attributionSpace.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    battery = new ShadeBattery(r, ctx, Color.WHITE);
    battery.view.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    accessibility = new BatteryAccessibility(original,
        description -> ready ? battery.completedDescription(description) : description);
    battery.view.setPivotX(0);
    battery.view.setPivotY(0);
    // Composite the AOSP fill and track before applying the status icon's alpha.
    battery.view.setLayerType(View.LAYER_TYPE_HARDWARE, null);
    setClipChildren(false);
    setClipToPadding(false);
    addView(battery.view, new LayoutParams(-2, -2));
    observer = new ContentObserver(r.main) {
      @Override public void onChange(boolean self) { readStyle(); }
    };
  }

  @Override protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    accessibility.attach();
    r.homeBattery.observeNeighbour(this, bar, keyguard);
    getViewTreeObserver().addOnPreDrawListener(this);
    // The keyguard meter receives wallpaper-aware tint from its own icon pipeline.
    if (!keyguard) {
      try {
        statusTint = new StatusBarBatteryTint(r, this);
        statusTint.attach();
      } catch (Throwable e) { releaseTint(); warn("tint", e); }
    }
    try {
      getContext().registerReceiver(users, new IntentFilter("android.intent.action.USER_SWITCHED"));
      receiverRegistered = true;
      observeUser();
    } catch (RuntimeException e) { warn("user", e); }
  }

  @Override protected void onDetachedFromWindow() {
    accessibility.detach();
    r.homeBattery.forgetNeighbour(this);
    removeCallbacks(applyGap);
    removeCallbacks(checkIcons);
    pendingGap = false;
    if (icons != null) {
      icons.removeOnLayoutChangeListener(iconLayout);
      applyIconGap(false);
      icons = null;
      endPadding = null;
    }
    mobileAdjacent = false;
    if (getViewTreeObserver().isAlive()) getViewTreeObserver().removeOnPreDrawListener(this);
    if (receiverRegistered) { getContext().unregisterReceiver(users); receiverRegistered = false; }
    if (settings != null) { settings.unregisterContentObserver(observer); settings = null; }
    releaseTint();
    setExtraWidth(0);
    chargingIndicator.setHidden(false);
    ready = false;
    super.onDetachedFromWindow();
  }

  private void warn(String part, Throwable error) {
    r.homeBattery.warning(keyguard ? "keyguard " + part : part, error);
  }

  private void releaseTint() {
    if (statusTint == null) return;
    try { statusTint.detach(); }
    catch (ReflectiveOperationException e) { warn("tint detach", e); }
    statusTint = null;
  }

  private void observeUser() {
    if (settings != null) { settings.unregisterContentObserver(observer); settings = null; }
    try {
      Context user = (Context)Reflect.method(Context.class, "createContextAsUser", UserHandle.class, int.class)
          .invoke(getContext(), r.currentUser(), 0);
      ContentResolver resolver = user.getContentResolver();
      resolver.registerContentObserver(Settings.System.getUriFor("display_power_percent"), false, observer);
      settings = resolver;
      readStyle();
    } catch (Throwable e) { warn("percentage", e); }
  }

  private void readStyle() {
    if (settings == null) return;
    int style = Settings.System.getInt(settings, "display_power_percent", -1);
    try { battery.showDigits(CombinedStatusIcons.insidePercent(style)); }
    catch (ReflectiveOperationException e) { fallback(e); }
  }

  private void fallback(Throwable e) {
    failed = true;
    ready = false;
    scheduleGap();
    requestLayout();
    invalidate();
    warn("display", e);
  }

  static int extraWidth(int renderedWidth, int normalWidth, float scale) {
    return (int)Math.ceil(Math.max(0, renderedWidth - normalWidth) * scale);
  }

  private boolean setExtraWidth(int width) {
    if (width == extraWidth) return false;
    extraWidth = width;
    if (width == 0) {
      original.removeView(attributionSpace);
    } else if (attributionSpace.getParent() == null) {
      // The ROM places the external percentage after both alternate glyph slots.
      int index = Math.max(original.indexOfChild(icon), original.indexOfChild(oldStyle)) + 1;
      original.addView(attributionSpace, index, new LinearLayout.LayoutParams(width, 0));
    } else {
      ViewGroup.LayoutParams params = attributionSpace.getLayoutParams();
      params.width = width;
      attributionSpace.setLayoutParams(params);
    }
    return true;
  }

  @Override public boolean onPreDraw() {
    boolean displayed = updateBatteryArtwork();
    if (ready != displayed) {
      ready = displayed;
      scheduleGap();
    }
    // A width change needs one layout pass before the bar can draw at its new position.
    boolean changed = chargingIndicator.setHidden(ready);
    changed |= setExtraWidth(ready ? requiredExtraWidth : 0);
    return !changed;
  }

  private int requiredExtraWidth;

  private boolean updateBatteryArtwork() {
    if (failed || original.getVisibility() != VISIBLE) return false;
    View slot = icon.getVisibility() == VISIBLE ? icon : oldStyle;
    if (slot.getVisibility() != VISIBLE) return false;
    try {
      Object model = currentIcon.invoke(original);
      if (model == null) return false;
      int color = (int)tintColor.invoke(model);
      if (statusTint != null) color = statusTint.color(slot, color);
      int foreground = color | 0xff000000;
      if (foreground != lastForeground) {
        battery.tint(foreground);
        lastForeground = foreground;
      }
      bounds.set(0, 0, slot.getWidth(), slot.getHeight());
      slot.getMatrix().mapRect(bounds);
      bounds.offset(slot.getLeft() + (float)contentOffset.invoke(original), slot.getTop());
      original.getMatrix().mapRect(bounds);
      bounds.offset(original.getLeft(), original.getTop());
      View view = battery.view;
      if (view.getWidth() <= 0 || view.getHeight() <= 0) return false;
      int neutralWidth = frameSize.normalWidth(view.getHeight());
      float scale = BatteryFrameSize.fitScale(neutralWidth, view.getHeight(), bounds.width(), bounds.height());
      if (scale <= 0) return false;
      view.setScaleX(scale);
      view.setScaleY(scale);
      requiredExtraWidth = extraWidth(view.getWidth(), neutralWidth, scale);
      float left = bounds.centerX() - neutralWidth * scale / 2f;
      if (getLayoutDirection() == LAYOUT_DIRECTION_RTL) left -= (view.getWidth() - neutralWidth) * scale;
      view.setTranslationX(artworkLeft(left, keyguard,
          getLayoutDirection() == LAYOUT_DIRECTION_RTL, getResources().getDisplayMetrics().density));
      view.setTranslationY(bounds.centerY() - view.getHeight() * scale / 2f);
      view.setAlpha(artworkAlpha(color, original.getAlpha(), slot.getAlpha()));
      return true;
    } catch (Exception e) { fallback(e); return false; }
  }

  // OOS keeps updating the ImageView's visibility, tint and drawable on charger changes.
  // Keep it bound so the OEM fallback always has the current charging state.
  static final class ChargingIndicator {
    private final View view;
    private LinearLayout.LayoutParams savedLayout;
    private Rect savedClip;

    ChargingIndicator(View view) { this.view = view; }

    boolean setHidden(boolean hide) {
      if (view == null || hide == (savedLayout != null)) return false;
      if (hide) {
        if (!(view.getLayoutParams() instanceof LinearLayout.LayoutParams params)) return false;
        savedLayout = params;
        savedClip = view.getClipBounds();
        LinearLayout.LayoutParams collapsed = new LinearLayout.LayoutParams(params);
        collapsed.width = 0;
        collapsed.weight = 0;
        collapsed.leftMargin = collapsed.rightMargin = 0;
        collapsed.setMarginStart(0);
        collapsed.setMarginEnd(0);
        view.setClipBounds(new Rect());
        view.setLayoutParams(collapsed);
      } else {
        view.setClipBounds(savedClip);
        view.setLayoutParams(savedLayout);
        savedLayout = null;
        savedClip = null;
      }
      return true;
    }
  }

  static float artworkAlpha(int color, float meterAlpha, float slotAlpha) {
    return Color.alpha(color) / 255f * meterAlpha * slotAlpha;
  }

  void watchIcons(ViewGroup group) {
    icons = group;
    endPadding = new EndPadding(group);
    group.addOnLayoutChangeListener(iconLayout);
    post(checkIcons);
  }

  void neighbour(String slot, boolean mobile) {
    adjacentSlot = slot;
    mobileAdjacent = mobile;
    scheduleGap();
  }

  private void scheduleGap() {
    if (icons == null || pendingGap) return;
    int desired = cellInset(ready, mobileAdjacent, getResources().getDisplayMetrics().density);
    if (!endPadding.needs(desired)) return;
    pendingGap = true;
    post(applyGap);
  }

  private void applyIconGap(boolean cellular) {
    if (icons == null) return;
    endPadding.set(cellInset(true, cellular, getResources().getDisplayMetrics().density));
  }

  // Remember the unmodified OEM value independently of the last value we wrote.
  static final class EndPadding {
    final ViewGroup group;
    int base, last, extra;
    EndPadding(ViewGroup group) {
      this.group = group;
      base = last = group.getPaddingEnd();
    }

    boolean needs(int desired) {
      return desired != extra || group.getPaddingEnd() != last;
    }

    void set(int desired) {
      int current = group.getPaddingEnd();
      if (current != last) base = current;
      extra = desired;
      last = base + desired;
      if (current != last) group.setPaddingRelative(group.getPaddingStart(),
          group.getPaddingTop(), last, group.getPaddingBottom());
    }
  }

  String spacingReport() {
    if (icons == null) return "no icon container";
    return "slot=" + adjacentSlot + " replacement=" + ready + " base=" + endPadding.base
        + "px extra=" + endPadding.extra + "px actualEnd=" + icons.getPaddingEnd() + "px";
  }

  static int cellInset(boolean replacement, boolean mobile, float density) {
    return replacement && mobile ? Math.round(2f * density) : 0;
  }

  static int slotWidth(int meterWidth, float density) {
    return Math.max(0, meterWidth - Math.round(2f * density));
  }

  static float artworkLeft(float centered, boolean keyguard, boolean rtl, float density) {
    if (!keyguard) return centered;
    int shift = Math.round(2f * density);
    return centered + (rtl ? shift : -shift);
  }

  @Override protected void onMeasure(int width, int height) {
    if (original.getVisibility() == GONE) { setMeasuredDimension(0,0); return; }
    original.measure(width, height);
    int unbounded = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
    battery.view.measure(unbounded, unbounded);
    int contentWidth = original.getMeasuredWidth();
    int widthNeeded = failed ? contentWidth : slotWidth(contentWidth, getResources().getDisplayMetrics().density);
    setMeasuredDimension(resolveSize(widthNeeded, width), original.getMeasuredHeight());
  }

  @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
    // Anchor the complete OEM meter to the logical end, including any charge/percentage siblings.
    int left = getLayoutDirection() == LAYOUT_DIRECTION_RTL ? 0 : r - l - original.getMeasuredWidth();
    original.layout(left, 0, left + original.getMeasuredWidth(), original.getMeasuredHeight());
    battery.view.layout(0, 0, battery.view.getMeasuredWidth(), battery.view.getMeasuredHeight());
  }

  @Override protected boolean drawChild(Canvas canvas, View child, long time) {
    if (!ready) return child == original && super.drawChild(canvas, child, time);
    if (child != original) return super.drawChild(canvas, child, time);
    int save = canvas.save();
    try {
      canvas.clipOutRect(bounds);
      return super.drawChild(canvas, child, time);
    } finally { canvas.restoreToCount(save); }
  }
}
