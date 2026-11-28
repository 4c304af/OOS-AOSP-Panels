package dev.oos.nativepanels;

import android.graphics.Typeface;
import android.widget.TextView;

final class ClockTypeface {
  private static final String ROBOTO = "/system/fonts/Roboto-Regular.ttf";
  private static final String ROBOTO_FLEX = "/system/fonts/RobotoFlex-Regular.ttf";
  private static final Resolved FILE_FONT = resolveFile();
  private static final Resolved STATUS_CLOCK = resolve("variable-label-large-emphasized");
  private static final Resolved QS_CLOCK = resolve("variable-display-small-emphasized");

  private ClockTypeface() {}

  static boolean apply(TextView clock) {
    return apply(clock, STATUS_CLOCK.face());
  }

  static boolean applyQs(TextView clock) {
    boolean changed = apply(clock, QS_CLOCK.face());
    if (clock.getLetterSpacing() != .01f) {
      clock.setLetterSpacing(.01f);
      changed = true;
    }
    return changed;
  }

  static String report() { return STATUS_CLOCK.source() + " weight=600"; }

  private static boolean apply(TextView clock, Typeface face) {
    if (face.equals(clock.getTypeface())) return false;
    clock.setTypeface(face);
    return true;
  }

  private static Resolved resolveFile() {
    Typeface face = fromFile(ROBOTO, false);
    if (face != null) return new Resolved(face, ROBOTO);
    face = fromFile(ROBOTO_FLEX, true);
    return face == null ? null : new Resolved(face, ROBOTO_FLEX);
  }

  static Typeface fromFile(String path, boolean flex) {
    try {
      // These files bypass OEM sans-serif aliases. Apply the glyph axes as well
      // as the requested style; metadata alone does not make a variable font bold.
      Typeface base = new Typeface.Builder(path)
          .setFontVariationSettings(flex ? "'wght' 600, 'wdth' 100, 'opsz' 14" : "'wght' 600, 'wdth' 100")
          .setItalic(false).build();
      // Derive from the file's actual style so a static regular fallback can
      // still receive synthetic weight instead of being relabeled as semibold.
      return base == null ? null : Typeface.create(base, 600, false);
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  private static Resolved resolve(String family) {
    if (FILE_FONT != null) return FILE_FONT;
    Typeface named = Typeface.create(family, Typeface.NORMAL);
    return new Resolved(Typeface.create(named, 600, false), "family:" + family);
  }

  private record Resolved(Typeface face, String source) {}
}
