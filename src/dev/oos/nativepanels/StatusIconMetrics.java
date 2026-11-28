package dev.oos.nativepanels;

import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

/** Geometry for Android 17's updated status icons, independent of OEM drawable slots. */
final class StatusIconMetrics {
  private StatusIconMetrics() {}

  static int sp(View view, float value) {
    return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value,
        view.getResources().getDisplayMetrics()));
  }

  static Drawable signal(ImageView view, Drawable art, float aspectRatio) {
    int height = Math.max(1, sp(view, 12f));
    int width = Math.max(1, Math.round(height * aspectRatio));
    view.setAdjustViewBounds(true);
    view.setScaleType(ImageView.ScaleType.FIT_CENTER);
    view.setMinimumWidth(0);
    view.setMinimumHeight(0);
    view.setPadding(0, 0, 0, 0);
    ViewGroup.LayoutParams p = view.getLayoutParams();
    if (p != null && (p.width != ViewGroup.LayoutParams.WRAP_CONTENT || p.height != height)) {
      p.width = ViewGroup.LayoutParams.WRAP_CONTENT;
      p.height = height;
      view.setLayoutParams(p);
    }
    return new NativeIconDrawable(art, width, height, aspectRatio);
  }

  static Drawable mobileSignal(ImageView view, DrawableWrapper glyph, Drawable levels, int state) {
    // Selecting a five-bar level can change the LevelList's intrinsic width
    // from 17dp to 21dp. Read its aspect ratio after the native level decoder.
    glyph.setLevel(state);
    Drawable result = signal(view, glyph,
        levels.getIntrinsicWidth() / (float)levels.getIntrinsicHeight());
    result.setLevel(state);
    return result;
  }

  static void group(View view) { margins(view, sp(view, 3f), sp(view, 3f)); }

  static void mobile(View group, TextView type, ImageView signal) {
    group(group);
    // AOSP's updated RAT container leaves 6sp before the bars. OOS puts a
    // separate 0.66dp start margin on the bars' frame, so replace both values.
    margins(type, 0, sp(type, 6f));
    if (signal.getParent() instanceof View frame && frame.getParent() == type.getParent()) {
      margins(frame, 0, 0);
    }
  }

  static View namedAncestor(View view, String name) {
    int id = view.getResources().getIdentifier(name, "id", Protocol.TARGET);
    if (id == 0) return null;
    for (View current = view; current != null;
        current = current.getParent() instanceof View parent ? parent : null) {
      if (current.getId() == id) return current;
    }
    return null;
  }

  static void configuration(View root, boolean mobile) {
    View child = named(root, mobile ? "mobile_signal" : "wifi_signal");
    if (!(child instanceof ImageView image)
        || !(image.getDrawable() instanceof NativeIconDrawable nativeIcon)) return;
    int level = nativeIcon.getLevel();
    Drawable refreshed = signal(image, nativeIcon.getDrawable(), nativeIcon.aspectRatio);
    image.setImageDrawable(refreshed);
    refreshed.setLevel(level);
    View group = named(root, mobile ? "mobile_group" : "wifi_group");
    if (mobile && named(root, "mobile_type_text") instanceof TextView type && group != null)
      mobile(group, type, image);
    else if (group != null) group(group);
  }

  private static View named(View root, String name) {
    int id = root.getResources().getIdentifier(name, "id", Protocol.TARGET);
    return id == 0 ? null : root.findViewById(id);
  }

  private static void margins(View view, int start, int end) {
    if (view == null || !(view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams p)) return;
    if (p.getMarginStart() == start && p.getMarginEnd() == end) return;
    p.setMarginStart(start);
    p.setMarginEnd(end);
    view.setLayoutParams(p);
  }

  static final class NativeIconDrawable extends DrawableWrapper {
    private final int width, height;
    private final float aspectRatio;
    NativeIconDrawable(Drawable art, int width, int height, float aspectRatio) {
      super(art);
      this.width = width;
      this.height = height;
      this.aspectRatio = aspectRatio;
    }
    @Override public int getIntrinsicWidth() { return width; }
    @Override public int getIntrinsicHeight() { return height; }
  }
}
