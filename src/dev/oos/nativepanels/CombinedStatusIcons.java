package dev.oos.nativepanels;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import java.lang.reflect.Method;

/** An independent QS icon group, using the same dependencies as the ROM header. */
final class CombinedStatusIcons implements View.OnAttachStateChangeListener {
  private final PanelRuntime r;
  private final LinearLayout row;
  private final View percentFallback;
  private ViewGroup icons;
  private ShadeBattery battery;
  private View batteryHost;
  private View chip;
  private Object manager, controller, privacy;
  private Method getSlot, addGroup, removeGroup;
  private boolean registered, listening;
  private String lastMobile = "unseen";
  private android.content.ContentResolver settings;
  private final android.database.ContentObserver percentObserver;

  CombinedStatusIcons(PanelRuntime r, LinearLayout row, View percent) {
    this.r = r;
    this.row = row;
    percentFallback = percent;
    percentObserver = new android.database.ContentObserver(r.main) {
      @Override public void onChange(boolean selfChange) { updatePercent(); }
    };
  }

  void bind(Object header, int tint) throws Throwable {
    if (manager != null) return;
    Context context = row.getContext();
    Class<?> containerType = r.type("com.android.systemui.statusbar.phone.StatusIconContainer");
    Class<?> location = r.type("com.android.systemui.statusbar.phone.StatusBarLocation");
    getSlot = r.type("com.android.systemui.statusbar.StatusIconDisplayable").getMethod("getSlot");
    Object factory = Reflect.get(header, "tintedIconManagerFactory");
    Object iconController = Reflect.get(header, "statusBarIconController");
    icons = (ViewGroup) containerType.getConstructor(Context.class).newInstance(context);
    Reflect.method(containerType, "setShouldRestrictIcons", boolean.class).invoke(icons, false);
    // The enclosing shade animates the whole row. An interrupted OOS hide animation can
    // otherwise send a hidden state to the mobile binding after it has become visible.
    Reflect.method(containerType, "setEnableAnimation", boolean.class).invoke(icons, false);
    Object group = Reflect.method(factory.getClass(), "create", ViewGroup.class, location)
        .invoke(factory, icons, location.getField("QS").get(null));
    
    row.addView(icons, row.indexOfChild(percentFallback), params(24));
    manager = group;
    try {
      battery = new ShadeBattery(r, context, tint);
      batteryHost = QsBatteryView.forHeader(r, header, battery.view);
      row.addView(batteryHost, row.indexOfChild(percentFallback), params(-2));
      percentFallback.setVisibility(View.GONE);
    } catch (Throwable e) { battery = null; r.combinedQs.layout.warn(e); }
    try {
      Class<?> type = r.type("com.android.systemui.privacy.OngoingPrivacyChip");
      chip = (View) type.getConstructor(Context.class).newInstance(context);
      chip.setVisibility(View.GONE);
      Object pf = r.provider("headerPrivacyIconsControllerFactoryProvider");
      privacy = Reflect.method(pf.getClass(), "createHeaderPrivacyIconsController", type, containerType)
          .invoke(pf, chip, icons);
      row.addView(chip, params(28));
    } catch (Throwable e) { privacy = null; r.combinedQs.layout.warn(e); }
    updateSpacing();
    tint(tint);
    observeGroup(iconController, r.type("com.android.systemui.statusbar.phone.ui.IconManager"));
  }

  void observeGroup(Object iconController, Class<?> managerType) throws ReflectiveOperationException {
    controller = iconController;
    addGroup = Reflect.method(controller.getClass(), "addIconGroup", managerType);
    removeGroup = Reflect.method(controller.getClass(), "removeIconGroup", managerType);
    icons.addOnAttachStateChangeListener(this);
    if (icons.isAttachedToWindow()) attached(true);
  }

  // A GONE child and its margins take no space. Keep the gap on the chip's
  // logical start so it follows both kinds of indicator and RTL layouts.
  boolean chipVisible() { return listening && chip != null && chip.isShown(); }

  void updateSpacing() {
    if (batteryHost != null && batteryHost.getLayoutParams() instanceof LinearLayout.LayoutParams batteryParams) {
      int gap = Math.round(2f * row.getResources().getDisplayMetrics().density);
      if (gap != batteryParams.getMarginStart()) {
        batteryParams.setMarginStart(gap);
        batteryHost.setLayoutParams(batteryParams);
      }
    }
    if (chip == null || !(chip.getLayoutParams() instanceof LinearLayout.LayoutParams p)) return;
    var res = row.getResources();
    int gapId = res.getIdentifier("qs_privacy_to_header_view_margin", "dimen", Protocol.TARGET);
    int gap = gapId == 0 ? Math.round(4f * res.getDisplayMetrics().density) : res.getDimensionPixelSize(gapId);
    if (gap != p.getMarginStart()) {
      p.setMarginStart(gap);
      chip.setLayoutParams(p);
    }
  }

  private LinearLayout.LayoutParams params(int dp) {
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, dp < 0 ? dp : Math.round(dp * row.getResources().getDisplayMetrics().density));
    p.gravity = Gravity.CENTER_VERTICAL;
    return p;
  }

  void tint(int color) {
    if (manager == null) return;
    try {
      // OplusSplitShadeHeaderControllerEx.applyDarkness uses textColorPrimaryInverse for cutouts.
      android.content.res.TypedArray colors = row.getContext().obtainStyledAttributes(new int[]{android.R.attr.textColorPrimaryInverse});
      int inverse;
      try { inverse = colors.getColor(0, color); } finally { colors.recycle(); }
      if (darkIntensity(inverse) == darkIntensity(color)) {
        inverse = darkIntensity(color) == 0f ? android.graphics.Color.BLACK : android.graphics.Color.WHITE;
      }
      Reflect.method(manager.getClass(), "setTint", int.class, int.class).invoke(manager, color, inverse);
      if (battery != null) battery.tint(color);
    } catch (Throwable e) { r.combinedQs.layout.warn(e); }
  }

  static float darkIntensity(int foreground) {
    return android.graphics.Color.luminance(foreground) < 0.5f ? 1f : 0f;
  }

  // AndroidView can replace or reparent the header while the outer QS fragment stays attached.
  @Override public void onViewAttachedToWindow(View v) { attached(true); }
  @Override public void onViewDetachedFromWindow(View v) { attached(false); }

  void dispose() {
    if (icons != null) icons.removeOnAttachStateChangeListener(this);
    attached(false);
  }

  private void attached(boolean attached) {
    if (!attached) releasePercent();
    if (manager == null || attached == registered) return;
    try {
      (attached ? addGroup : removeGroup).invoke(controller, manager);
      registered = attached;
      if (attached) bindUser();
    } catch (Throwable e) { r.combinedQs.layout.warn(e); }
  }

  private void releasePercent() {
    if (settings != null) {
      settings.unregisterContentObserver(percentObserver);
      settings = null;
    }
  }

  void bindUser() {
    releasePercent();
    if (battery == null || !registered) return;
    try {
      Context user = (Context)Reflect.invoke(Reflect.method(Context.class, "createContextAsUser",
          android.os.UserHandle.class, int.class), row.getContext(), r.currentUser(), 0);
      android.content.ContentResolver resolver = user.getContentResolver();
      resolver.registerContentObserver(android.provider.Settings.System.getUriFor("display_power_percent"), false, percentObserver);
      settings = resolver;
      updatePercent();
    } catch (Throwable e) { r.combinedQs.layout.warn(e); }
  }

  // The AOSP shade normally has digits inside. Preserve the user's explicit OOS preference.
  static boolean insidePercent(int style) { return style != 0 && style != 2; }

  private void updatePercent() {
    if (settings == null || battery == null) return;
    try {
      int style = android.provider.Settings.System.getInt(settings, "display_power_percent", -1);
      battery.showDigits(insidePercent(style));
      percentFallback.setVisibility(style == 2 ? View.VISIBLE : View.GONE);
    } catch (Throwable e) { r.combinedQs.layout.warn(e); }
  }

  void active(boolean active) {
    if (manager == null) return;
    try {
      if (active) {
        if (batteryHost instanceof QsBatteryView fitted) fitted.refreshSlot();
        if (icons.isAttachedToWindow()) attached(true);
        icons.requestLayout();
        icons.postOnAnimation(this::sample);
      }
      if (privacy != null && active != listening) {
        if (active) {
          Reflect.call(privacy, "onParentVisible");
          Reflect.call(privacy, "startListening");
        } else {
          Reflect.call(privacy, "stopListening");
          Reflect.call(privacy, "onParentInvisible");
        }
        listening = active;
      }
    } catch (Throwable e) { r.combinedQs.layout.warn(e); }
  }

  void sample() {
    if (icons == null || !icons.isShown() || icons.getWidth() == 0) return;
    StringBuilder mobile = new StringBuilder();
    for (int i = 0; i < icons.getChildCount(); i++) {
      View v = icons.getChildAt(i);
      String slot;
      try { slot = (String)getSlot.invoke(v); }
      catch (ReflectiveOperationException | IllegalArgumentException e) { continue; }
      if (!HomeBatteryHook.mobileSlot(slot)) continue;
      if (mobile.length() > 0) mobile.append(',');
      mobile.append(slot).append('=').append(v.getVisibility()).append('/').append(v.getWidth()).append('/').append(v.getAlpha());
    }
    lastMobile = mobile.length() == 0 ? "no holder" : mobile.toString();
  }

  String status() {
    if (android.os.Looper.myLooper() == r.main.getLooper()) sample();
    return manager == null ? "unbound" : icons.getChildCount() + " mobile(last)=" + lastMobile
        + " battery=" + (battery == null ? "percent" : "shade-compose");
  }
}
