package dev.oos.nativepanels;

/** Geometry of the neutral SystemUI battery, including its cap. */
record BatteryFrameSize(float bodyWidth, float bodyHeight, float capWidth) {
  static BatteryFrameSize read(PanelRuntime r) throws ReflectiveOperationException {
    Object frame = r.type("com.android.systemui.statusbar.pipeline.battery.shared.ui.BatteryFrame").getField("INSTANCE").get(null);
    Object body = Reflect.method(frame.getClass(), "getBodyPathSpec").invoke(frame);
    Object cap = Reflect.method(frame.getClass(), "getCapPathSpec").invoke(frame);
    float bodyWidth = body.getClass().getField("viewportWidth").getFloat(body);
    float bodyHeight = body.getClass().getField("viewportHeight").getFloat(body);
    float capWidth = cap.getClass().getField("viewportWidth").getFloat(cap);
    return new BatteryFrameSize(bodyWidth, bodyHeight, capWidth);
  }

  // Keep the replacement inside the OOS glyph's rectangle at its original aspect ratio.
  static float fitScale(float width, float height, float slotWidth, float slotHeight) {
    if (width <= 0 || height <= 0 || slotWidth <= 0 || slotHeight <= 0) return 0f;
    return .80f * Math.min(slotWidth / width, slotHeight / height);
  }

  // The Android 17 Compose battery already measures its native 13sp height.
  // Only the real parent's available space may reduce it; an OEM glyph slot
  // must neither enlarge it nor apply a second, arbitrary shrink factor.
  static float intrinsicScale(float width, float height, float availableWidth, float availableHeight) {
    if (width <= 0 || height <= 0 || availableWidth <= 0 || availableHeight <= 0) return 0f;
    return Math.min(1f, Math.min(availableWidth / width, availableHeight / height));
  }

  // BatteryMeasurePolicy uses the same frame in both states. Only its trailing
  // child changes from a cap to an attribution, so fit against the neutral width.
  int normalWidth(int height) {
    if (height <= 0 || bodyHeight <= 0f) return 0;
    float unit = height / bodyHeight;
    return Math.round(bodyWidth * unit) + Math.round(capWidth * unit) + Math.round(unit);
  }
}
