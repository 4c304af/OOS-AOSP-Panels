package dev.oos.nativepanels;

import android.view.View;
import android.view.ViewGroup;
import java.lang.reflect.Field;

/** Relink only OOS 17's roaming → bars → network-type chain; keep every native view in place. */
final class MobileNetworkTypeOrder {
  private static final int UNSET = -1;
  private static final int PARENT = 0;

  private final Class<?> params;
  private final Field startToStart;
  private final Field startToEnd;
  private final Field endToStart;
  private final Field endToEnd;
  private final Field[] absoluteAnchors;

  MobileNetworkTypeOrder(Class<?> params) throws NoSuchFieldException {
    this.params = params;
    startToStart = params.getField("startToStart");
    startToEnd = params.getField("startToEnd");
    endToStart = params.getField("endToStart");
    endToEnd = params.getField("endToEnd");
    absoluteAnchors = new Field[]{params.getField("leftToLeft"), params.getField("leftToRight"),
        params.getField("rightToLeft"), params.getField("rightToRight")};
  }

  boolean apply(ViewGroup group, View type) throws IllegalAccessException {
    if (group == null || type == null || type.getParent() != group || type.getId() <= 0) return false;
    ViewGroup.LayoutParams tp = type.getLayoutParams();
    if (!isRelative(tp) || startToStart.getInt(tp) != UNSET || startToEnd.getInt(tp) <= 0
        || endToStart.getInt(tp) != UNSET || endToEnd.getInt(tp) != PARENT) return false;

    View bars = group.findViewById(startToEnd.getInt(tp));
    if (bars == null || bars == type || bars.getParent() != group || bars.getId() <= 0) return false;
    ViewGroup.LayoutParams bp = bars.getLayoutParams();
    if (!isRelative(bp) || startToStart.getInt(bp) != UNSET || startToEnd.getInt(bp) <= 0
        || endToStart.getInt(bp) != type.getId() || endToEnd.getInt(bp) != UNSET) return false;

    View roaming = group.findViewById(startToEnd.getInt(bp));
    if (roaming == null || roaming == type || roaming == bars || roaming.getParent() != group
        || roaming.getId() <= 0) return false;
    ViewGroup.LayoutParams rp = roaming.getLayoutParams();
    if (!isRelative(rp) || startToStart.getInt(rp) != PARENT || startToEnd.getInt(rp) != UNSET
        || endToStart.getInt(rp) != bars.getId() || endToEnd.getInt(rp) != UNSET) return false;

    // Relative constraints keep the same chain, margins, binder references and RTL behavior.
    endToStart.setInt(rp, type.getId());
    startToEnd.setInt(tp, roaming.getId());
    endToStart.setInt(tp, bars.getId());
    endToEnd.setInt(tp, UNSET);
    startToEnd.setInt(bp, type.getId());
    endToStart.setInt(bp, UNSET);
    endToEnd.setInt(bp, PARENT);
    roaming.setLayoutParams(rp);
    type.setLayoutParams(tp);
    bars.setLayoutParams(bp);
    return true;
  }

  private boolean isRelative(ViewGroup.LayoutParams lp) throws IllegalAccessException {
    if (!params.isInstance(lp)) return false;
    for (Field anchor : absoluteAnchors) if (anchor.getInt(lp) != UNSET) return false;
    return true;
  }
}
