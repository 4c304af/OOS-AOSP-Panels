package dev.oos.nativepanels;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import java.text.NumberFormat;
import java.util.Locale;

final class ShadeDecor implements View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
  final FrameLayout frame;
  final BrightnessPreview preview;
  final boolean hasAutomaticBrightness;
  Object buttonFactory, headerFactory;
  private FrameLayout headerHost;
  private final SplitShadeChrome.SharedHeader sharedHeader = new SplitShadeChrome.SharedHeader();
  private int lastReserve = -1, lastSharedWidth, lastSharedHeight;
  private float headerTranslation;
  private int orientation;
  private boolean refreshInsets = true;
  private final SplitShadeChrome splitChrome = new SplitShadeChrome();
  private int lastPortraitInset = -1, lastSplitInset = -1;
  boolean splitLayout() { return layout != null && layout.notificationScrim.splitFor(frame); }

  boolean orientationChangedTo(View next) {
    return orientation != next.getResources().getConfiguration().orientation;
  }

  private void checkOrientation() {
    int now = context.getResources().getConfiguration().orientation;
    if (orientation == now) return;
    orientation = now;
    refreshInsets = true;
    resetPlacement(true);
    updateHostHeight();
    frame.requestApplyInsets();
  }

  private void resetPlacement(boolean rotated) {
    resetContentMotion();
    headerTranslation = 0f;
    if (headerHost != null) headerHost.setTranslationY(0f);
    restoreSharedHeader();
    splitChrome.restore();
    if (layout != null) layout.resetGeometry(this, rotated);
  }
  private View movingContent;
  private float addedMotion, appliedMotion;
  private final int[] hostOrigin = new int[2];
  Object slider;
  int dragStarts, mirrorClears, hapticRequests, hapticAccepted;
  boolean dragging;
  final java.util.WeakHashMap<Object, Boolean> hapticTiles = new java.util.WeakHashMap<>();
  Object brightnessHaptics, brightnessHapticProvider;
  int brightnessBand = -1;
  long lastTick;
  final int panelPaddingTop;
  private final int panelVerticalPadding;
  private final int selectedRadius, unselectedRadius;
  private final ShadeLayout layout;
  private final PanelRuntime runtime;
  private final Context context;
  private final LinearLayout header;
  private final TextClock clock, date;
  private Object clockGraph;
  private final android.graphics.Rect clockBounds = new android.graphics.Rect();
  private final TextView battery;
  record ButtonColors(int activeBackground, int activeIcon, int inactiveBackground, int inactiveIcon) {}
  private final java.util.Map<ImageButton, ButtonColors> buttons = new java.util.WeakHashMap<>();
  private ButtonColors fallbackColors;
  private boolean brightnessWritable;
  private final CombinedStatusIcons statusIcons;
  private boolean headerBound;
  private float fraction, clockScale = 2.57f;
  private long retryBrightnessAt;
  private final TextClock expandedClock;
  private final int horizontal, vertical;
  private final int[] location = new int[2];
  private Locale locale;
  private int top, sideLeft, sideRight, foreground;
  private boolean open, lockedShade, listening, disposed, automatic;
  private ContentResolver resolver;
  private final ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
    @Override public void onChange(boolean selfChange) { readMode(); }
  };
  private final BroadcastReceiver changes = new BroadcastReceiver() {
    @Override public void onReceive(Context c, Intent intent) {
      if (Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) updateBattery(intent);
      else if (runtime != null) {
        preview.reset();
        if (statusIcons != null) statusIcons.bindUser();
        layout.media.stop();
        if (open && !lockedShade) layout.media.start(context);
        bindBrightness();
      }
    }
  };

  ShadeDecor(FrameLayout frame, ShadeLayout layout, PanelRuntime runtime) {
    this.frame = frame;
    this.preview = new BrightnessPreview(this, layout, runtime);
    this.layout = layout;
    this.runtime = runtime;
    context = frame.getContext();
    orientation = context.getResources().getConfiguration().orientation;
    panelPaddingTop = id("qs_panel_padding_top", "dimen");
    panelVerticalPadding = id("overlay_qs_layout_vertical_padding", "dimen");
    selectedRadius = buttonRadius("common_tile_default_active_icon_corner_radius", 16);
    unselectedRadius = buttonRadius("common_tile_default_inactive_icon_corner_radius", 24);
    horizontal = id("qs_tile_margin_horizontal", "dimen");
    vertical = id("qs_tile_margin_vertical", "dimen");
    int available = context.getResources().getIdentifier("config_automatic_brightness_available", "bool", "android");
    android.hardware.SensorManager sensors = context.getSystemService(android.hardware.SensorManager.class);
    int glyph = id("status_bar_qs_brightness_icon_auto", "drawable");
    hasAutomaticBrightness = glyph != 0 && ((available != 0 && context.getResources().getBoolean(available))
        || (sensors != null && sensors.getDefaultSensor(android.hardware.Sensor.TYPE_LIGHT) != null));
    header = new LinearLayout(context) {
      @Override protected void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        checkOrientation();
        if (statusIcons != null) statusIcons.updateSpacing();
      }
    };
    header.setGravity(Gravity.CENTER_VERTICAL);
    clock = headerClock(context);
    expandedClock = headerClock(context);
    expandedClock.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    expandedClock.setSingleLine(true);
    expandedClock.setIncludeFontPadding(false);
    expandedClock.setVisibility(View.INVISIBLE);
    int scaleId = id("qqs_expand_clock_scale", "dimen");
    if (scaleId != 0) {
      android.util.TypedValue scale = new android.util.TypedValue();
      context.getResources().getValue(scaleId, scale, true);
      if (scale.type == android.util.TypedValue.TYPE_FLOAT && scale.getFloat() >= 1f) clockScale = scale.getFloat();
    }
    expandedClock.setTextSize(14f * clockScale);
    date = new TextClock(context);
    battery = new TextView(context);
    for (TextView text : new TextView[]{clock, date, battery}) {
      text.setSingleLine(true);
      text.setTextSize(14);
      text.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
      text.setGravity(Gravity.CENTER_VERTICAL);
      text.setIncludeFontPadding(false);
    }
    date.setEllipsize(TextUtils.TruncateAt.END);
    header.addView(clock, new LinearLayout.LayoutParams(-2, -1));
    LinearLayout.LayoutParams fill = new LinearLayout.LayoutParams(0, -2, 1f);
    fill.setMarginStart(dp(10));
    fill.setMarginEnd(dp(8));
    header.addView(date, fill);
    header.addView(battery);
    statusIcons = runtime == null ? null : new CombinedStatusIcons(runtime, header, battery);
    header.setVisibility(View.INVISIBLE);
  }

  ImageButton createBrightnessButton(Context host) {
    if (fallbackColors == null) style();
    ImageButton auto = new ImageButton(host);
    int glyph = id("status_bar_qs_brightness_icon_auto", "drawable");
    if (hasAutomaticBrightness) {
      int letter = id("status_bar_brightness_auto_mode_icon_alphabet", "drawable");
      if (letter == 0) auto.setImageResource(glyph);
      else auto.setImageDrawable(new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{
          context.getDrawable(glyph), context.getDrawable(letter)}));
    }
    int padding = dp(12);
    auto.setPadding(padding, padding, padding, padding);
    auto.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
    auto.setOnClickListener(this::toggleMode);
    auto.setAccessibilityDelegate(new View.AccessibilityDelegate() {
      @Override public void onInitializeAccessibilityNodeInfo(View v, android.view.accessibility.AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(v, info);
        info.setClassName("android.widget.Switch");
        info.setCheckable(true);
        info.setChecked(automatic);
      }
    });
    buttons.put(auto, null);
    try { auto.setContentDescription(runtime.text(R.string.automatic_brightness)); }
    catch (RuntimeException ignored) {
      int label = id("state_button_brightness", "string");
      auto.setContentDescription(label == 0 ? "Automatic brightness" : context.getText(label));
    }
    auto.setEnabled(brightnessWritable);
    auto.setAlpha(brightnessWritable ? 1f : .5f);
    paintButton(auto);
    return auto;
  }

  private int id(String name, String type) {
    return context.getResources().getIdentifier(name, type, context.getPackageName());
  }
  private int dp(float value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }

  private int buttonRadius(String key, int fallback) {
    int radius = id(key, "dimen");
    return Math.min(dp(24), radius == 0 ? dp(fallback) : context.getResources().getDimensionPixelSize(radius));
  }

  boolean isTileGap(int id) { return id != 0 && (id == horizontal || id == vertical); }

  int headerHeight() {
    return top + Math.max(dp(36), Math.round(Math.max(clock.getLineHeight(), battery.getLineHeight()) + dp(8)));
  }

  int gridHeaderHeight() { return sharedHeader != null && sharedHeader.placed() ? sharedHeader.reserve : headerHeight(); }

  int expandedGridHeaderHeight(boolean split) {
    int header = split ? gridHeaderHeight() : expandedHeight();
    // The expanded Column adds its own vertical padding after the header spacer. Credit
    // part of that padding to the spacer without shifting the clock or crossing its bounds.
    int padding = panelVerticalPadding == 0 ? 0 : context.getResources().getDimensionPixelSize(panelVerticalPadding);
    return expandedSpacer(header, padding, dp(split ? 2 : 8));
  }

  static int expandedSpacer(int header, int panelPadding, int credit) {
    return Math.max(0, header - Math.min(panelPadding, credit));
  }

  private void restoreSharedHeader() {
    if (!sharedHeader.placed()) return;
    sharedHeader.restore(header, headerHost);
    header.setPadding(sideLeft + dp(16), top, sideRight + dp(16), 0);
    header.setLayoutParams(new FrameLayout.LayoutParams(-1, headerHeight(), Gravity.TOP));
    refreshInsets = true;
    lastReserve = -1;
    updateHostHeight();
    layout.measuredHeader(headerHeight(), expandedHeight());
  }

  int expandedHeight() {
    if (splitLayout()) return headerHeight();
    return headerHeight() + Math.max(dp(44), expandedClock.getLineHeight() + dp(6));
  }

  void expansion(float value) {
    float progress = Float.isFinite(value) ? Math.max(0f, Math.min(1f, value)) : 0f;
    if (fraction == progress) return;
    fraction = progress;
    updateHostHeight();
    frame.invalidate();
  }

  void headerTranslation(float value) {
    headerTranslation = (layout == null || open) && Float.isFinite(value) ? value : 0f;
    if (layout == null) syncHeaderTranslation();
  }

  private int visibleHeaderHeight() {
    if (sharedHeader.placed()) return sharedHeader.reserve;
    return headerHeight() + Math.round((expandedHeight() - headerHeight()) * fraction);
  }

  private void updateHostHeight() {
    if (headerHost == null) return;
    android.view.ViewGroup.LayoutParams params = headerHost.getLayoutParams();
    int height = visibleHeaderHeight();
    if (params != null && params.height != height) {
      params.height = height;
      headerHost.setLayoutParams(params);
    }
  }

  // A ROM may already apply part of the native offset to the Compose content.
  // The AndroidView holder tracks its Compose node's position, independently
  // of the translation on our child. Supply only the distance still missing.
  static float remainingTranslation(float target, float inherited) {
    if (target < 0f) return Math.min(0f, target - Math.min(0f, inherited));
    if (target > 0f) return Math.max(0f, target - Math.max(0f, inherited));
    return 0f;
  }

  private void resetContentMotion() {
    if (movingContent != null && addedMotion != 0f && movingContent.getTranslationY() == appliedMotion)
      movingContent.setTranslationY(appliedMotion - addedMotion);
    movingContent = null;
    addedMotion = appliedMotion = 0f;
  }

  private void syncContentMotion() throws ReflectiveOperationException {
    View content = frame.getChildCount() > 0 ? frame.getChildAt(0) : null;
    if (content != movingContent) resetContentMotion();
    if (content == null || content == headerHost || runtime.settingsLaunch.isTracking(frame)) {
      resetContentMotion();
      syncHeaderTranslation();
      return;
    }
    float target = layout.contentTranslation();
    if (!Float.isFinite(target)) {
      resetContentMotion();
      syncHeaderTranslation();
      return;
    }
    if (target == 0f) {
      resetContentMotion();
      if (headerHost != null && headerHost.getTranslationY() != 0f) headerHost.setTranslationY(0f);
      return;
    }
    movingContent = content;
    float current = content.getTranslationY();
    if (current != appliedMotion) addedMotion = 0f;
    // The holder and the tiles share the native Content Box. Its measured
    // position includes the modifier offset on ROMs which already place it.
    boolean embedded = headerHost != null && headerHost.isLaidOut()
        && headerHost.getParent() instanceof View && headerHost.getParent() != frame;
    View anchor = embedded ? (View) headerHost.getParent() : content;
    anchor.getLocationInWindow(hostOrigin);
    frame.getLocationInWindow(location);
    float inherited = hostOrigin[1] - location[1] + frame.getTranslationY() - addedMotion;
    if (frame.getParent() instanceof View parent) inherited += parent.getTranslationY();
    float offset = Math.round(remainingTranslation(target, inherited));
    appliedMotion = current - addedMotion + offset;
    addedMotion = offset;
    if (current != appliedMotion) content.setTranslationY(appliedMotion);
    if (headerHost != null) {
      float headerOffset = embedded ? 0f : remainingTranslation(target, frame.getTranslationY());
      if (headerHost.getTranslationY() != headerOffset) headerHost.setTranslationY(headerOffset);
    }
  }

  private void syncHeaderTranslation() {
    if (headerHost == null) return;
    float inherited = 0f;
    if (headerHost.isLaidOut() && headerHost.getParent() instanceof View parent) {
      parent.getLocationInWindow(hostOrigin);
      frame.getLocationInWindow(location);
      inherited = hostOrigin[1] - location[1] + frame.getTranslationY();
    }
    headerHost.setTranslationY(remainingTranslation(headerTranslation, inherited));
  }

  void positionHeader(float lockProgress, float parentTranslation) {
    header.setVisibility(View.VISIBLE);
    boolean direct = Float.isFinite(lockProgress);
    float p = direct ? 1f : splitLayout() ? 0f : fraction;
    int extra = expandedHeight() - headerHeight();
    float opacity = LockscreenQsMotion.chromeAlpha(lockProgress);
    if (runtime != null && clockGraph != runtime.graph) bindClockAction();
    boolean clockVisible = open && opacity > 0f && !preview.visible();
    clockTarget(clock, clockVisible && p < .5f);
    clockTarget(expandedClock, clockVisible && p >= .5f);
    if (!sharedHeader.placed()) header.setAlpha(opacity);
    header.setTranslationY(extra * p + (direct
        ? LockscreenQsMotion.offset(lockProgress, dp(LockscreenQsMotion.HEADER_DP), parentTranslation) : 0f));
    clock.setAlpha(1f - p);
    clock.setImportantForAccessibility(p < 0.5f ? View.IMPORTANT_FOR_ACCESSIBILITY_YES : View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    float dateOpacity = p < .14f ? 1f - p / .14f : Math.max(0f, (p - .58f) / .42f);
    date.setAlpha(dateOpacity);
    date.setTranslationX((header.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? 1 : -1)
        * (clock.getWidth() + dp(10)) * p);
    expandedClock.setVisibility(p > 0f ? View.VISIBLE : View.INVISIBLE);
    expandedClock.setImportantForAccessibility(p >= 0.5f ? View.IMPORTANT_FOR_ACCESSIBILITY_YES : View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    expandedClock.setAlpha(p * opacity);
    float scale = (1f + (clockScale - 1f) * p) / clockScale;
    expandedClock.setPivotX(frame.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? expandedClock.getWidth() : 0f);
    expandedClock.setPivotY(expandedClock.getHeight() / 2f);
    expandedClock.setScaleX(scale);
    expandedClock.setScaleY(scale);
    expandedClock.setTranslationY(top + (direct
        ? LockscreenQsMotion.offset(lockProgress, dp(LockscreenQsMotion.CLOCK_DP), parentTranslation) : 0f));
  }

  void install() {
    style();
    // Estimate the inset before the first composition; the measured insets take over at layout.
    int status = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
    top = status == 0 ? 0 : context.getResources().getDimensionPixelSize(status);
    header.setPadding(dp(16), top, dp(16), 0);
    header.setLayoutParams(new FrameLayout.LayoutParams(-1, headerHeight(), Gravity.TOP));
    FrameLayout.LayoutParams big = new FrameLayout.LayoutParams(-2, expandedHeight() - headerHeight(), Gravity.TOP | Gravity.START);
    big.setMarginStart(dp(16));
    expandedClock.setLayoutParams(big);
    frame.addOnAttachStateChangeListener(this);
    if (frame.isAttachedToWindow()) onViewAttachedToWindow(frame);
  }

  FrameLayout createHeaderHost(Context context) {
    FrameLayout next = new FrameLayout(context);
    next.setClipChildren(false);
    next.setClipToPadding(false);
    next.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    next.setLayoutParams(new FrameLayout.LayoutParams(-1, visibleHeaderHeight(), Gravity.TOP));
    // AndroidView factories must return a fresh root after a composition is
    // recreated. The icon group belongs to this fragment and moves as one row.
    if (headerHost != null) {
      headerHost.removeView(header);
      headerHost.removeView(expandedClock);
    }
    if (!sharedHeader.placed()) next.addView(header);
    next.addView(expandedClock);
    next.setTranslationY(open && !splitLayout() ? headerTranslation : 0f);
    headerHost = next;
    return next;
  }

  private int styledNightMode = -1;

  private void style() {
    locale = context.getResources().getConfiguration().getLocales().get(0);
    clock.setFormat12Hour(DateFormat.getBestDateTimePattern(locale, "hm"));
    clock.setFormat24Hour(DateFormat.getBestDateTimePattern(locale, "Hm"));
    expandedClock.setFormat12Hour(clock.getFormat12Hour());
    expandedClock.setFormat24Hour(clock.getFormat24Hour());
    String day = DateFormat.getBestDateTimePattern(locale, "MMMEd");
    date.setFormat12Hour(day);
    date.setFormat24Hour(day);
    styledNightMode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
    boolean dark = styledNightMode == Configuration.UI_MODE_NIGHT_YES;
    foreground = context.getColor(dark ? android.R.color.system_neutral1_100 : android.R.color.system_neutral1_900);
    fallbackColors = systemButtonColors(dark);
    for (TextView text : new TextView[]{clock, date, battery}) text.setTextColor(foreground);
    expandedClock.setTextColor(foreground);
    buttons.keySet().forEach(this::paintButton);
    if (statusIcons != null) statusIcons.tint(foreground);
  }

  void bindHeader(Object controller) {
    if (headerBound || statusIcons == null) return;
    try {
      statusIcons.bind(controller, foreground);
      headerBound = true;
      statusIcons.active(listening);
    } catch (Throwable e) { layout.warn(e); }
  }

  private static TextClock headerClock(Context context) {
    return new TextClock(context) {
      @Override public CharSequence getAccessibilityClassName() {
        return isClickable() ? android.widget.Button.class.getName() : super.getAccessibilityClassName();
      }
    };
  }

  private void bindClockAction() {
    clockGraph = runtime.graph;
    View.OnClickListener action = null;
    if (clockGraph != null) {
      try {
        Object interactor = runtime.provider("clockInteractorProvider");
        var launch = Reflect.method(interactor.getClass(), "launchClockActivity");
        action = source -> {
          if (!open || disposed || !source.isShown() || !source.isClickable() || preview.visible()) return;
          try { Reflect.invoke(launch, interactor); }
          catch (Throwable failure) { layout.warn(failure); }
        };
      } catch (Throwable failure) { layout.warn(failure); }
    }
    clock.setOnClickListener(action);
    expandedClock.setOnClickListener(action);
  }

  private static void clockTarget(TextClock view, boolean active) {
    boolean target = active && view.hasOnClickListeners();
    if (view.isClickable() != target) view.setClickable(target);
    if (view.isFocusable() != target) view.setFocusable(target);
  }

  boolean hitClock(float x, float y) {
    TextClock target = expandedClock.isClickable() ? expandedClock : clock;
    if (!open || !target.isClickable() || !target.isShown() || !target.getGlobalVisibleRect(clockBounds)) return false;
    target.getRootView().getLocationInWindow(location);
    return clockBounds.contains((int)Math.floor(x - location[0]), (int)Math.floor(y - location[1]));
  }

  boolean hitControl(float x, float y) {
    int[] position = new int[2];
    for (ImageButton button : buttons.keySet()) {
      android.graphics.Rect visible = new android.graphics.Rect();
      if (!button.isShown() || !button.getLocalVisibleRect(visible)) continue;
      button.getLocationInWindow(position);
      if (visible.contains((int) Math.floor(x - position[0]), (int) Math.floor(y - position[1]))) return true;
    }
    return false;
  }

  void feedback(int effect) {
    hapticRequests++;
    if (frame.performHapticFeedback(effect)) hapticAccepted++;
  }

  String report() {
    return "QS layout: headerInContent=" + (headerHost != null && headerHost.isAttachedToWindow() && headerHost.getParent() != frame) + " split=" + splitLayout() + " lastSplitHeader=" + lastSharedWidth + "x" + lastSharedHeight + " oosIconsHidden=" + splitChrome.suppressed() + " inset(portrait/split)=" + lastPortraitInset + "/" + lastSplitInset + " headerY=" + headerTranslation + " header=" + header.getHeight() + "px mode=" + (hasAutomaticBrightness ? "supported" : "unavailable")
        + " iconGroup=" + (statusIcons == null ? "none" : statusIcons.status()) + " brightnessInSlider=" + buttons.keySet().stream().anyMatch(View::isAttachedToWindow) + " brightnessObserver=" + (resolver != null)
        + " inlineDragStarts=" + dragStarts + " previews=" + preview.shown + " previewActive=" + preview.visible() + " mirrorClears=" + mirrorClears
        + " hapticsAccepted/requests=" + hapticAccepted + "/" + hapticRequests;
  }

  void setOpen(boolean value, boolean restricted) {
    if (open == value && lockedShade == restricted) return;
    boolean changedLock = lockedShade != restricted;
    boolean opening = value && !open && !restricted;
    if (layout != null && value && !restricted && (!open || changedLock)) layout.cards.beginPull();
    lockedShade = restricted;
    open = value;
    if (layout != null && (!value || restricted)) {
      layout.touch.cancelGestures();
      layout.notificationScrim.endPull();
      layout.cards.clear();
    }
    if (changedLock && restricted) {
      preview.reset();
      if (layout != null) layout.media.onShadeLocked();
      unbindBrightness();
    } else if (changedLock && value && listening) {
      if (layout != null) layout.media.start(context);
      bindBrightness();
    }
    if (opening && layout != null && !preview.visible() && frame.isAttachedToWindow()) {
      try {
        if (!layout.isEditing()) layout.notificationScrim.update(frame, layout.shadeExpansion, fraction);
      } catch (Exception e) { layout.warn(e); }
    }
    if (frame.isAttachedToWindow()) listen(open);
    if (!value || restricted) { restoreSharedHeader(); splitChrome.restore(); }
    if (!value) {
      resetPlacement(false);
      if (statusIcons != null) statusIcons.sample();
      brightnessBand = -1;
      dragging = false;
      preview.reset();
      header.setVisibility(View.INVISIBLE);
      expandedClock.setVisibility(View.INVISIBLE);
    }
  }

  @Override public void onViewAttachedToWindow(View view) {
    frame.getViewTreeObserver().addOnPreDrawListener(this);
    if (layout != null) frame.post(() -> {
      if (!disposed && frame.isAttachedToWindow()) {
        try { layout.notificationScrim.attachLayer(frame); }
        catch (RuntimeException e) { layout.warn(e); }
      }
    });
    listen(open);
  }

  @Override public void onViewDetachedFromWindow(View view) {
    resetPlacement(false);
    if (layout != null) {
      layout.cards.clear();
      layout.notificationScrim.release();
      layout.media.stop();
    }
    preview.reset();
    if (frame.getViewTreeObserver().isAlive()) frame.getViewTreeObserver().removeOnPreDrawListener(this);
    listen(false);
  }

  void dispose() {
    disposed = true;
    if (statusIcons != null) statusIcons.dispose();
    restoreSharedHeader();
    preview.reset();
    if (frame.isAttachedToWindow()) onViewDetachedFromWindow(frame);
    frame.removeOnAttachStateChangeListener(this);
    if (headerHost != null) {
      headerHost.removeView(header);
      headerHost.removeView(expandedClock);
      if (headerHost.getParent() == frame) frame.removeView(headerHost);
      headerHost = null;
    }
    headerFactory = null;
    buttonFactory = null;
    buttons.clear();
    slider = null;
    hapticTiles.clear();
    brightnessHaptics = null;
    brightnessHapticProvider = null;
  }

  private void listen(boolean enable) {
    if (listening == enable) return;
    if (layout != null && enable && !lockedShade) layout.media.start(context);
    if (enable) {
      style();
      if (statusIcons != null) statusIcons.active(true);
      IntentFilter f = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
      f.addAction("android.intent.action.USER_SWITCHED");
      Intent sticky = context.registerReceiver(changes, f);
      listening = true;
      if (sticky != null && Intent.ACTION_BATTERY_CHANGED.equals(sticky.getAction())) updateBattery(sticky);
      bindBrightness();
    } else {
      listening = false;
      if (statusIcons != null) statusIcons.active(false);
      context.unregisterReceiver(changes);
      unbindBrightness();
    }
  }

  private void updateBattery(Intent intent) {
    int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
    int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 0);
    battery.setText(level < 0 || scale <= 0 ? "" : NumberFormat.getPercentInstance(locale).format((double) level / scale));
  }

  private void enableBrightness(boolean enable) {
    brightnessWritable = enable;
    for (ImageButton button : buttons.keySet()) {
      button.setEnabled(enable);
      button.setAlpha(enable ? 1f : .5f);
    }
  }

  private void unbindBrightness() {
    enableBrightness(false);
    if (resolver != null) {
      resolver.unregisterContentObserver(observer);
      resolver = null;
    }
  }

  private void bindBrightness() {
    retryBrightnessAt = android.os.SystemClock.uptimeMillis() + 2000;
    unbindBrightness();
    if (!hasAutomaticBrightness || lockedShade) return;
    try {
      Context user;
      try {
        Object tracker = runtime.provider("provideUserTrackerProvider");
        user = (Context) Reflect.call(tracker, "getUserContext");
      } catch (ReflectiveOperationException | IllegalStateException unavailable) {
        int id = (Integer) Reflect.method(android.app.ActivityManager.class, "getCurrentUser").invoke(null);
        android.os.UserHandle handle = (android.os.UserHandle) Reflect.method(android.os.UserHandle.class, "of", int.class).invoke(null, id);
        user = (Context) Reflect.method(Context.class, "createContextAsUser", android.os.UserHandle.class, int.class)
            .invoke(context, handle, 0);
      }
      UserManager users = user.getSystemService(UserManager.class);
      if (users != null && users.hasUserRestriction("no_config_brightness")) return;
      ContentResolver target = user.getContentResolver();
      target.registerContentObserver(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE), false, observer);
      resolver = target;
      readMode();
      enableBrightness(true);
    } catch (Throwable e) { layout.warn(e); }
  }

  private void readMode() {
    if (resolver == null) return;
    automatic = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
    buttons.keySet().forEach(this::paintButton);
  }

  private ButtonColors systemButtonColors(boolean dark) {
    if (android.os.Build.VERSION.SDK_INT >= 34) {
      return new ButtonColors(
          context.getColor(dark ? android.R.color.system_primary_dark : android.R.color.system_primary_light),
          context.getColor(dark ? android.R.color.system_on_primary_dark : android.R.color.system_on_primary_light),
          context.getColor(dark ? android.R.color.system_surface_container_high_dark : android.R.color.system_surface_container_high_light),
          context.getColor(dark ? android.R.color.system_on_surface_dark : android.R.color.system_on_surface_light));
    }
    android.util.TypedValue accent = new android.util.TypedValue();
    context.getTheme().resolveAttribute(android.R.attr.colorAccent, accent, true);
    return new ButtonColors(accent.data, dark ? android.graphics.Color.BLACK : android.graphics.Color.WHITE,
        context.getColor(dark ? android.R.color.background_dark : android.R.color.background_light), foreground);
  }

  void setButtonColors(ImageButton button, ButtonColors colors) {
    if (java.util.Objects.equals(buttons.put(button, colors), colors)) return;
    paintButton(button);
  }

  private void paintButton(ImageButton auto) {
    GradientDrawable background = new GradientDrawable();
    background.setCornerRadius(automatic ? selectedRadius : unselectedRadius);
    ButtonColors colors = buttons.get(auto);
    if (colors == null) colors = fallbackColors;
    background.setColor(automatic ? colors.activeBackground() : colors.inactiveBackground());
    auto.setBackground(new RippleDrawable(ColorStateList.valueOf(0x30888888), background, null));
    auto.setSelected(automatic);
    auto.setImageTintList(ColorStateList.valueOf(automatic ? colors.activeIcon() : colors.inactiveIcon()));
  }

  private void toggleMode(View source) {
    if (lockedShade || (runtime != null && runtime.locked())) return;
    bindBrightness();
    if (resolver == null) return;
    try {
      Object controller = null;
      java.lang.reflect.Method setter = null;
      try {
        controller = runtime.provider("autoBrightnessControllerProvider");
        setter = Reflect.method(controller.getClass(), "setAutoBrightnessOn", boolean.class);
      } catch (ReflectiveOperationException | IllegalStateException unavailable) {
        // Older controller graphs can still expose the same per-user Settings contract.
      }
      if (setter != null) setter.invoke(controller, !automatic);
      else if (!Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, automatic
          ? Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL : Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)) return;
      source.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
      // The OOS controller writes asynchronously. The observer paints the committed value.
    } catch (Throwable e) { layout.warn(e); }
  }

  @Override public boolean onPreDraw() {
    if (preview.visible() && (!frame.hasWindowFocus() || runtime.locked())) preview.reset();
    if (disposed) return true;
    checkOrientation();
    if (!open) return true;
    if ((context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) != styledNightMode) style();
    try {
      boolean editing = layout.isEditing();
      boolean showSplit = !lockedShade && !editing && !preview.visible() && splitLayout() && frame.isShown();
      if (showSplit) sharedHeader.place(frame, header, headerHost, dp(16));
      else restoreSharedHeader();
      if (sharedHeader.placed() && lastReserve != sharedHeader.reserve) {
        lastReserve = sharedHeader.reserve;
        updateHostHeight();
        layout.measuredHeader(lastReserve, lastReserve);
      }
      splitChrome.showNativeHeader(frame, headerBound && showSplit);
      splitChrome.syncPrivacy(sharedHeader.placed() && statusIcons != null && statusIcons.chipVisible());
      if (lockedShade || editing || preview.visible()) layout.notificationScrim.hide();
      else layout.notificationScrim.update(frame, layout.shadeExpansion, fraction);
      boolean cardsEnabled = runtime.enabled("notification_cards");
      layout.cards.setEnabled(cardsEnabled);
      if (lockedShade || editing || preview.visible() || !cardsEnabled || (!layout.notificationScrim.isSplit() && fraction >= 1f)) layout.cards.clear();
      else layout.cards.update(frame);
      if (editing) {
        resetContentMotion();
        header.setVisibility(View.INVISIBLE);
        expandedClock.setVisibility(View.INVISIBLE);
        return true;
      }
    } catch (Exception e) { layout.warn(e); }
    WindowInsets window = frame.getRootWindowInsets();
    if (!sharedHeader.placed() && window != null) {
      Insets insets = window.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars() | WindowInsets.Type.displayCutout());
      // Translation during a pull is transient. Reserve the same top space throughout the gesture.
      int frameTop = SplitShadeChrome.layoutCoordinate(frame, false);
      int frameLeft = SplitShadeChrome.layoutCoordinate(frame, true);
      int t = Math.max(0, insets.top - Math.max(0, frameTop));
      int l = Math.max(0, insets.left - frameLeft);
      int r = Math.max(0, insets.right - (frame.getRootView().getWidth() - frameLeft - frame.getWidth()));
      if (splitLayout()) lastSplitInset = t; else lastPortraitInset = t;
      if (refreshInsets || t != top || l != sideLeft || r != sideRight) {
        refreshInsets = false;
        top = t;
        sideLeft = l;
        sideRight = r;
        header.setPadding(l + dp(16), t, r + dp(16), 0);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) header.getLayoutParams();
        params.height = headerHeight();
        header.setLayoutParams(params);
        FrameLayout.LayoutParams big = (FrameLayout.LayoutParams) expandedClock.getLayoutParams();
        big.setMarginStart(dp(16) + (frame.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? r : l));
        big.height = expandedHeight() - headerHeight();
        expandedClock.setLayoutParams(big);
        updateHostHeight();
        layout.measuredHeader(headerHeight(), expandedHeight());
      }
    }
    try {
      if (preview.visible() || splitLayout()) {
        resetContentMotion();
        if (headerHost != null && headerHost.getTranslationY() != 0f) headerHost.setTranslationY(0f);
      } else syncContentMotion();
      if (headerHost != null && headerHost.getParent() == frame) headerHost.setAlpha(layout.qsAlpha());
      if (sharedHeader.placed()) {
        header.setAlpha(layout.qsAlpha());
        lastSharedWidth = header.getWidth();
        lastSharedHeight = sharedHeader.height;
      }
      float lockProgress = lockedShade && !preview.visible() ? layout.directLockProgress() : Float.NaN;
      float inherited = Float.isFinite(lockProgress) ? layout.contentTranslation() : 0f;
      positionHeader(lockProgress, inherited);
      if (!lockedShade && !buttons.isEmpty() && resolver == null && listening
          && android.os.SystemClock.uptimeMillis() >= retryBrightnessAt) bindBrightness();
    } catch (Exception e) {
      layout.warn(e);
    }
    return true;
  }
}
