package dev.oos.nativepanels;

import android.graphics.Canvas;
import android.graphics.Matrix;
import android.view.View;

/** Draw the notification list only inside the visible portion of its shade window. */
final class PortraitNotificationClip {
  private final Matrix panelToList = new Matrix();
  private final float[] edge = new float[2];
  private View list;
  private int bottom;
  private int draws, emptyDraws;

  void update(View panel, View stack, float height, float fraction) {
    if (!Float.isFinite(height) || height < 0 || !Float.isFinite(fraction) || fraction >= 1f
        || panel.getRootView() != stack.getRootView() || stack.getHeight() <= 0) {
      clear();
      return;
    }
    int limit = 0;
    if (fraction > 0f) {
      panelToList.reset();
      panel.transformMatrixToGlobal(panelToList);
      stack.transformMatrixToLocal(panelToList);
      edge[0] = 0f;
      edge[1] = height;
      panelToList.mapPoints(edge);
      limit = Math.max(0, Math.round(edge[1]));
    }
    if (limit >= stack.getHeight()) { clear(); return; }
    if (list == stack && bottom == limit) return;
    if (list != stack) clear();
    list = stack;
    bottom = limit;
    stack.invalidate();
  }

  void clear() {
    if (list != null) list.invalidate();
    list = null;
  }

  boolean appliesTo(View stack) { return list != null && list == stack; }

  void clip(Canvas canvas) {
    int x = list.getScrollX(), y = list.getScrollY();
    canvas.clipRect(x, y, x + list.getWidth(), y + bottom);
    draws++;
    if (bottom == 0) emptyDraws++;
  }

  String report() { return " portraitClip(draws/empty)=" + draws + "/" + emptyDraws; }
}
