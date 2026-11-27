package dev.oos.nativepanels;

import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Path;
import android.graphics.Rect;
import android.view.View;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Predicate;

/** Clip the OOS background, Seedling content and post-dispatch spotlight as one card. */
final class FluidCardClip {
  private Class<?> rowType;
  private final Path mask = new Path();
  private final Outline outline = new Outline();
  private final Rect bounds = new Rect();
  private final Set<View> clipped = Collections.newSetFromMap(new WeakHashMap<>());

  void install(PanelRuntime runtime, Predicate<View> active) throws ReflectiveOperationException {
    Class<?> type = runtime.type("com.oplus.systemui.statusbar.notification.customcard.OplusCustomRow");
    Method draw = type.getDeclaredMethod("dispatchDraw", Canvas.class);
    Method entry = Reflect.method(type, "getEntry");
    Method customView = Reflect.method(entry.getReturnType(), "getCustomView");
    Method height = Reflect.method(type, "getActualHeight");
    Method translation = Reflect.method(type, "getTranslation");
    Method guide = Reflect.method(type, "getGuideTipsController");
    Method tips = Reflect.method(guide.getReturnType(), "isTipsViewVisible");
    Field launching = Reflect.field(type, "isLaunchAnimationRunning");
    Field expanding = Reflect.field(type, "_isExpandAnimationRunning");
    runtime.module.hook(draw).setId("native-panels:fluid-card-clip")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
          View row = (View)chain.getThisObject();
          if (!active.test(row) || launching.getBoolean(row) || expanding.getBoolean(row)
              || (Float)translation.invoke(row) != 0f) return chain.proceed();
          Object item = entry.invoke(row);
          if (item == null || !(customView.invoke(item) instanceof View content) || content.getParent() != row
              || !content.isShown() || !content.getMatrix().isIdentity()
              || (Boolean)tips.invoke(guide.invoke(row))) return chain.proceed();
          Path clip = pathFor(content, (Integer)height.invoke(row));
          if (clip == null) return chain.proceed();
          clipped.add(row);
          Canvas canvas = (Canvas)chain.getArg(0);
          int save = canvas.save();
          try {
            canvas.clipPath(clip);
            return chain.proceed();
          } finally { canvas.restoreToCount(save); }
        });
    rowType = type;
  }

  Path pathFor(View content, int actualHeight) {
    int left = content.getLeft(), top = content.getTop(), right = content.getRight();
    int bottom = Math.min(content.getBottom(), actualHeight);
    if (right <= left || bottom <= top) return null;
    float radius = 28f * content.getResources().getDisplayMetrics().density;
    if (content.getOutlineProvider() != null) {
      outline.setEmpty();
      content.getOutlineProvider().getOutline(content, outline);
      if (outline.getRect(bounds) && bounds.left == 0 && bounds.top == 0
          && bounds.right == content.getWidth() && bounds.bottom == content.getHeight())
        radius = Math.max(radius, outline.getRadius());
    }
    radius = Math.min(radius, Math.min(right - left, bottom - top) / 2f);
    mask.rewind();
    mask.addRoundRect(left, top, right, bottom, radius, radius, Path.Direction.CW);
    return mask;
  }

  void invalidateIfRow(View view) {
    if (rowType != null && rowType.isInstance(view)) view.invalidate();
  }

  void clear() {
    for (View view : clipped) view.invalidate();
    clipped.clear();
  }
}
