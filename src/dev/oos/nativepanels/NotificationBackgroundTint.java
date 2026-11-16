package dev.oos.nativepanels;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

/** Recolors one native background draw while its renderer keeps animated bounds and clipping. */
final class NotificationBackgroundTint {
  interface Draw { Object run() throws Throwable; }

  private final Paint paint = new Paint();
  private final RectF bounds = new RectF();
  private static final ThreadLocal<Boolean> DRAWING = new ThreadLocal<>();

  void setColor(int color) {
    paint.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
  }

  Object draw(Canvas canvas, View background, int width, int height, Draw original) throws Throwable {
    // drawChild receives row coordinates. Bound the layer to this background, including
    // its current transform, rather than allocating a layer for the notification stack.
    bounds.set(0, 0, Math.max(background.getWidth(), width), Math.max(background.getHeight(), height));
    background.getMatrix().mapRect(bounds);
    View parent = background.getParent() instanceof View view ? view : null;
    bounds.offset(background.getLeft() - (parent == null ? 0 : parent.getScrollX()),
        background.getTop() - (parent == null ? 0 : parent.getScrollY()));
    return drawLayer(canvas, original);
  }

  Object draw(Canvas canvas, Rect drawableBounds, Draw original) throws Throwable {
    // Drawable.draw already receives the overlay View's local canvas.
    bounds.set(drawableBounds);
    return drawLayer(canvas, original);
  }

  private Object drawLayer(Canvas canvas, Draw original) throws Throwable {
    // Different registered classes can call an already-hooked superclass draw.
    if (Boolean.TRUE.equals(DRAWING.get())) return original.run();
    if (bounds.isEmpty()) return original.run();
    int save;
    try { save = canvas.saveLayer(bounds, paint); }
    catch (RuntimeException unavailable) { return original.run(); }
    DRAWING.set(true);
    try { return original.run(); }
    finally {
      DRAWING.remove();
      canvas.restoreToCount(save);
    }
  }
}
