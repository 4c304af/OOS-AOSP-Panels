package dev.oos.nativepanels;

import android.content.res.Configuration;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/** Use native Android 17 geometry, or fit the battery to the older OOS header slot. */
final class QsBatteryView extends FrameLayout {
  private final View artwork, icon, oldStyle;
  private final BatteryFrameSize frame;
  private final boolean nativeStatusBar;
  private int slotWidth, slotHeight;
  private final OnLayoutChangeListener slotLayout = (v, l, t, r, b, ol, ot, or, ob) -> refreshSlot();

  static View forHeader(PanelRuntime runtime, Object controller, View artwork) {
    boolean nativeStatusBar = runtime != null && runtime.statusBar.enabled(artwork.getContext());
    try {
      if (Reflect.field(controller.getClass(), "header").get(controller) instanceof View header) {
        View meter = namedChild(header, "batteryRemainingIcon");
        if (meter != null) {
          View icon = namedChild(meter, "battery_icon_view");
          View oldStyle = namedChild(meter, "battery_content");
          if (icon != null && oldStyle != null)
            return new QsBatteryView(artwork, icon, oldStyle, BatteryFrameSize.read(runtime), nativeStatusBar);
        }
      }
    } catch (ReflectiveOperationException | RuntimeException unavailable) {
      // An alternate header can still use SystemUI's intrinsic Compose battery size.
    }
    return nativeStatusBar ? new QsBatteryView(artwork, null, null, null, true) : artwork;
  }

  private static View namedChild(View parent, String name) {
    int id = parent.getResources().getIdentifier(name, "id", Protocol.TARGET);
    return id == 0 ? null : parent.findViewById(id);
  }

  QsBatteryView(View artwork, View icon, View oldStyle, BatteryFrameSize frame) {
    this(artwork, icon, oldStyle, frame, false);
  }

  QsBatteryView(View artwork, View icon, View oldStyle, BatteryFrameSize frame, boolean nativeStatusBar) {
    super(artwork.getContext());
    this.artwork = artwork;
    this.icon = icon;
    this.oldStyle = oldStyle;
    this.frame = frame;
    this.nativeStatusBar = nativeStatusBar;
    artwork.setPivotX(0f);
    artwork.setPivotY(0f);
    addView(artwork, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
  }

  @Override protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    if (icon != null) icon.addOnLayoutChangeListener(slotLayout);
    if (oldStyle != null) oldStyle.addOnLayoutChangeListener(slotLayout);
    refreshSlot();
  }

  @Override protected void onDetachedFromWindow() {
    if (icon != null) icon.removeOnLayoutChangeListener(slotLayout);
    if (oldStyle != null) oldStyle.removeOnLayoutChangeListener(slotLayout);
    super.onDetachedFromWindow();
  }

  @Override protected void onConfigurationChanged(Configuration config) {
    super.onConfigurationChanged(config);
    requestLayout();
  }

  void refreshSlot() {
    if (readSlot()) requestLayout();
  }

  private boolean readSlot() {
    if (icon == null || oldStyle == null) return false;
    View slot = icon.getVisibility() == VISIBLE ? icon : oldStyle;
    ViewGroup.LayoutParams p = slot.getLayoutParams();
    int width = 0, height = 0;
    if (slot.getVisibility() == VISIBLE && p != null) {
      // OOS updates these in pixels even when the original header is hidden.
      width = p.width > 0 ? p.width : slot.getMeasuredWidth();
      height = p.height > 0 ? p.height : slot.getMeasuredHeight();
    }
    boolean changed = width != slotWidth || height != slotHeight;
    slotWidth = width;
    slotHeight = height;
    return changed;
  }

  @Override protected void onMeasure(int widthSpec, int heightSpec) {
    readSlot();
    int unbounded = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
    artwork.measure(unbounded, unbounded);
    int width = artwork.getMeasuredWidth(), height = artwork.getMeasuredHeight();
    float scale = nativeStatusBar || frame == null ? 1f
        : BatteryFrameSize.fitScale(frame.normalWidth(height), height, slotWidth, slotHeight);
    if (scale <= 0f) scale = 1f;
    if (width > 0 && MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED)
      scale = Math.min(scale, MeasureSpec.getSize(widthSpec) / (float)width);
    if (height > 0 && MeasureSpec.getMode(heightSpec) != MeasureSpec.UNSPECIFIED)
      scale = Math.min(scale, MeasureSpec.getSize(heightSpec) / (float)height);
    artwork.setScaleX(scale);
    artwork.setScaleY(scale);
    setMeasuredDimension(resolveSize((int)Math.ceil(width * scale), widthSpec),
        resolveSize((int)Math.ceil(height * scale), heightSpec));
  }

  @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
    artwork.layout(0, 0, artwork.getMeasuredWidth(), artwork.getMeasuredHeight());
  }
}
