package dev.oos.nativepanels;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.widget.TextView;

final class ClockTypeface {
  private static volatile Typeface batteryClock;
  private static volatile String source = "battery glyph font pending";

  private ClockTypeface() {}

  static boolean apply(TextView clock) {
    Typeface face = resolve(clock.getContext());
    if (face == null || face.equals(clock.getTypeface())) return false;
    clock.setTypeface(face);
    return true;
  }

  static boolean applyQs(TextView clock) {
    boolean changed = apply(clock);
    if (clock.getLetterSpacing() != .01f) {
      clock.setLetterSpacing(.01f);
      changed = true;
    }
    return changed;
  }

  static String report() { return source; }

  private static Typeface resolve(Context context) {
    Typeface loaded = batteryClock;
    if (loaded != null) return loaded;
    try {
      // These outlines use BatteryGlyph's paths with lighter strokes.
      // A real font also keeps
      // StatClock's Paint.measureText width cache correct.
      Resources module = context.getPackageManager().getResourcesForApplication(BuildConfig.APPLICATION_ID);
      loaded = module.getFont(R.font.battery_clock);
      batteryClock = loaded;
      source = "battery_clock.otf BatteryGlyph light";
      return loaded;
    } catch (PackageManager.NameNotFoundException | Resources.NotFoundException unavailable) {
      source = "battery glyph font unavailable: " + unavailable.getClass().getSimpleName();
      return null;
    }
  }
}
