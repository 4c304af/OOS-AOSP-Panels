package dev.oos.nativepanels;

import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;
import java.util.Set;

/** Section boundaries use the adapter's visible rows, not the XML's unfiltered indexes. */
final class AboutPhoneRows {
  static final int SINGLE = 0, FIRST = 1, MIDDLE = 2, LAST = 3;
  private static final Set<String> HARDWARE = Set.of("device_market_name_header", "cpu_info",
      "ram_info", "camera_info", "processor_detail", "battery_and_charge_info",
      "screen_physics_size", "game_architecture", "security_chip_info", "tidal_architecture");

  record Item(String key, Object parent, boolean category) {}

  static boolean card(Item item) {
    return item != null && !item.category() && !"key_ota_update".equals(item.key());
  }

  static boolean joined(Item first, Item second) {
    if (!card(first) || !card(second) || first.parent() == null || first.parent() != second.parent())
      return false;
    // Root-level hardware is one section. Name/storage and the legal/version groups
    // already have real PreferenceCategory parents; do not join hardware to those.
    return hardware(first.key()) == hardware(second.key());
  }

  private static boolean hardware(String key) { return key != null && HARDWARE.contains(key); }

  static int position(Item before, Item current, Item after) {
    boolean top = joined(before, current), bottom = joined(current, after);
    return top ? (bottom ? MIDDLE : LAST) : (bottom ? FIRST : SINGLE);
  }

  static void radii(float[] result, int position, float outer, float inner) {
    float top = position == SINGLE || position == FIRST ? outer : inner;
    float bottom = position == SINGLE || position == LAST ? outer : inner;
    result[0] = result[1] = result[2] = result[3] = top;
    result[4] = result[5] = result[6] = result[7] = bottom;
  }

  static void path(Path path, RectF bounds, float[] radii, int width, int height, int inset) {
    float horizontal = Math.min(Math.max(0, inset), width / 2f);
    bounds.set(horizontal, 0, width - horizontal, height);
    path.reset();
    if (bounds.width() > 0 && bounds.height() > 0) path.addRoundRect(bounds, radii, Path.Direction.CCW);
  }

  static void spacing(View view, int gap) {
    if (!(view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams params)) return;
    // Absolute values are essential when COUI-Expressive is also installed and
    // when RecyclerView rebinds the same holder. Never add to another module's gap.
    if (params.topMargin == 0 && params.bottomMargin == gap) return;
    params.topMargin = 0;
    params.bottomMargin = gap;
    view.setLayoutParams(params);
  }

  private AboutPhoneRows() {}
}
