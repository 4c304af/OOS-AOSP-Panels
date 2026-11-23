package dev.oos.nativepanels;

import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ReplacementSpan;
import android.util.TypedValue;
import android.widget.TextView;
import java.util.Locale;
import java.util.WeakHashMap;

/** The Android 17 RAT artwork, inside the existing, accessible native label. */
final class AospNetworkTypeArtwork {
  private final WeakHashMap<TextView, Style> styled = new WeakHashMap<>();

  void apply(TextView view, Resources resources) {
    CharSequence label = view.getText();
    int resource = resourceFor(label);
    if (resource == 0) {
      Style original = styled.remove(view);
      if (original != null) original.restore(view);
      return;
    }
    Drawable art = resources.getDrawable(resource, null).mutate();
    // AOSP's updated mobile type container is 12sp high; preserve each vector's aspect ratio.
    int height = Math.max(1, Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
        12f, view.getResources().getDisplayMetrics())));
    int width = Math.max(1, Math.round(height * art.getIntrinsicWidth() / (float) art.getIntrinsicHeight()));
    art.setBounds(0, 0, width, height);
    styled.computeIfAbsent(view, Style::new);
    view.setIncludeFontPadding(false);
    view.setTextSize(TypedValue.COMPLEX_UNIT_PX, height);
    // Keep the real spelling for accessibility, diagnostics and subsequent native model updates.
    SpannableString text = new SpannableString(label.toString());
    text.setSpan(new ArtworkSpan(art), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    view.setText(text);
  }

  static int resourceFor(CharSequence label) {
    if (label == null) return 0;
    String name = label.toString().replaceAll("[\\s\\u00a0\\u200e\\u200f]", "")
        .toUpperCase(Locale.ROOT);
    return switch (name) {
      case "1X" -> R.drawable.aosp17_1x_mobiledata;
      case "G" -> R.drawable.aosp17_g_mobiledata;
      case "E" -> R.drawable.aosp17_e_mobiledata;
      case "H" -> R.drawable.aosp17_h_mobiledata;
      case "H+" -> R.drawable.aosp17_h_plus_mobiledata;
      case "3G" -> R.drawable.aosp17_3g_mobiledata;
      case "4G" -> R.drawable.aosp17_4g_mobiledata;
      case "4G+" -> R.drawable.aosp17_4g_plus_mobiledata;
      case "4GLTE" -> R.drawable.aosp17_4g_lte_mobiledata;
      case "4GLTE+" -> R.drawable.aosp17_4g_lte_plus_mobiledata;
      case "LTE" -> R.drawable.aosp17_lte_mobiledata;
      case "LTE+" -> R.drawable.aosp17_lte_plus_mobiledata;
      case "5GE" -> R.drawable.aosp17_5g_e_mobiledata;
      case "5G" -> R.drawable.aosp17_5g_mobiledata;
      case "5G+" -> R.drawable.aosp17_5g_plus_mobiledata;
      default -> 0; // Carrier brands and unsupported labels keep their actual native rendering.
    };
  }

  private record Style(float size, boolean fontPadding) {
    Style(TextView view) { this(view.getTextSize(), view.getIncludeFontPadding()); }
    void restore(TextView view) {
      view.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
      view.setIncludeFontPadding(fontPadding);
    }
  }

  static final class ArtworkSpan extends ReplacementSpan {
    private final Drawable art;
    ArtworkSpan(Drawable art) { this.art = art; }

    @Override public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
      if (fm != null) {
        fm.top = fm.ascent = -art.getBounds().height();
        fm.bottom = fm.descent = fm.leading = 0;
      }
      return art.getBounds().width();
    }

    @Override public void draw(Canvas canvas, CharSequence text, int start, int end,
        float x, int top, int y, int bottom, Paint paint) {
      // Native updateTint can run without rebinding the model. Read the current text paint every draw.
      art.setTint(paint.getColor());
      int saved = canvas.save();
      canvas.translate(x, y - art.getBounds().height());
      art.draw(canvas);
      canvas.restoreToCount(saved);
    }
  }
}
