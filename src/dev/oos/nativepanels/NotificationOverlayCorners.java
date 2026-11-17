package dev.oos.nativepanels;

import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Keeps the native overlay outline, fallback fill and blur geometry on one corner policy. */
final class NotificationOverlayCorners {
  // Values contain only weak instance references: a blur proxy itself retains its View.
  private final ArrayList<State> states = new ArrayList<>();
  private final Map<Class<?>, Map<String, Method>> methods = new HashMap<>();

  /** Native flush can clone a row fill that already carries our radii. Seed only that fresh clone. */
  void nativeFallbackSource(View target, float ownedTop, float ownedBottom, float nativeTop, float nativeBottom) {
    if (target == null || !radius(ownedTop) || !radius(ownedBottom) || !radius(nativeTop) || !radius(nativeBottom)) return;
    State state = find(target);
    try {
      Resolved resolved = resolve(target);
      if (resolved.fallback == null
          || (state != null && state.background != null && state.background.fallback.get() == resolved.fallback)) return;
      if (state == null) {
        state = new State(target);
        states.add(state);
      }
      if (state.background != null) state.background.restore();
      state.background = new Background(resolved);
      state.background.gradient.nativeSource(ownedTop, ownedBottom, nativeTop, nativeBottom);
    } catch (ReflectiveOperationException | RuntimeException unsupported) {
      if (state != null) {
        state.restore();
        states.remove(state);
      }
    }
  }

  /** Called on the UI thread only for a registry-verified native overlay. */
  boolean apply(View target, float top, float bottom) {
    if (target == null || !radius(top) || !radius(bottom)) return false;
    State state = find(target);
    try {
      Resolved resolved = resolve(target);
      if (state == null) {
        state = new State(target);
        states.add(state);
      }
      if (state.background == null || !state.background.matches(resolved)) {
        if (state.background != null) state.background.restore();
        state.background = new Background(resolved);
      }
      boolean outline = state.top.apply(top) | state.bottom.apply(bottom);
      boolean background = state.background.apply(top, bottom);
      if (outline) target.invalidateOutline();
      if (outline || background) target.invalidate();
      return true;
    } catch (ReflectiveOperationException | RuntimeException unsupported) {
      // A vendor variation must leave the rest of notification rendering available.
      if (state != null) {
        state.restore();
        states.remove(state);
      }
      return false;
    }
  }

  void restore(View target) {
    for (Iterator<State> iterator = states.iterator(); iterator.hasNext();) {
      State state = iterator.next();
      View current = state.target.get();
      if (current == null || current == target) {
        state.restore();
        iterator.remove();
      }
    }
  }

  void clear() {
    for (State state : states) state.restore();
    states.clear();
  }

  private State find(View target) {
    State found = null;
    for (Iterator<State> iterator = states.iterator(); iterator.hasNext();) {
      State state = iterator.next();
      View current = state.target.get();
      if (current == null) {
        state.restore();
        iterator.remove();
      } else if (current == target) found = state;
    }
    return found;
  }

  private Resolved resolve(View target) throws ReflectiveOperationException {
    Drawable wrapper = target.getBackground();
    if (wrapper == null || wrapper.getCallback() != target) throw new IllegalStateException("unowned overlay background");
    Object value = call(wrapper, "getDefaultDrawable");
    Drawable fallback = value instanceof Drawable ? (Drawable)value : null;
    if (value != null && !(fallback instanceof GradientDrawable))
      throw new IllegalStateException("unsupported overlay fallback shape");
    if (fallback != null && fallback.getCallback() != wrapper)
      throw new IllegalStateException("unowned overlay fallback");
    Object proxy = call(wrapper, "getViewBlurProxy");
    Object config = call(proxy, "getBlurConfig");
    Object stroke = call(config, "getGradientStrokeCornerParam");
    return new Resolved(wrapper, (GradientDrawable)fallback, proxy, config, stroke);
  }

  private Object call(Object receiver, String name) throws ReflectiveOperationException {
    if (receiver == null) throw new IllegalStateException("missing " + name);
    return method(receiver, name, false).invoke(receiver);
  }

  private Method method(Object receiver, String name, boolean setter) throws ReflectiveOperationException {
    if (receiver == null) throw new IllegalStateException("missing " + name);
    Map<String, Method> cache = methods.computeIfAbsent(receiver.getClass(), ignored -> new HashMap<>());
    String key = setter ? name + "(float)" : name;
    Method method = cache.get(key);
    if (method == null) {
      method = receiver.getClass().getMethod(name, setter ? new Class<?>[]{float.class} : new Class<?>[0]);
      cache.put(key, method);
    }
    return method;
  }

  private FloatProperty property(Object receiver, String suffix) throws ReflectiveOperationException {
    return new FloatProperty(receiver, method(receiver, "get" + suffix, false), method(receiver, "set" + suffix, true));
  }

  private record Resolved(Drawable wrapper, GradientDrawable fallback, Object proxy, Object config, Object stroke) {}

  private final class State {
    final WeakReference<View> target;
    final FloatProperty top, bottom;
    Background background;

    State(View view) throws ReflectiveOperationException {
      target = new WeakReference<>(view);
      top = property(view, "TopCornerRadius");
      bottom = property(view, "BottomCornerRadius");
    }

    void restore() {
      boolean outline = top.restore() | bottom.restore();
      boolean fill = background != null && background.restore();
      View view = target.get();
      if (view != null) {
        if (outline) view.invalidateOutline();
        if (outline || fill) view.invalidate();
      }
    }
  }

  private final class Background {
    final WeakReference<Drawable> wrapper;
    final WeakReference<Object> proxy, config, stroke;
    final WeakReference<GradientDrawable> fallback;
    final FloatProperty leftTop, rightTop, leftBottom, rightBottom, strokeRadius;
    final GradientShape gradient;

    Background(Resolved resolved) throws ReflectiveOperationException {
      wrapper = new WeakReference<>(resolved.wrapper);
      proxy = new WeakReference<>(resolved.proxy);
      config = new WeakReference<>(resolved.config);
      stroke = new WeakReference<>(resolved.stroke);
      fallback = new WeakReference<>(resolved.fallback);
      leftTop = property(resolved.config, "LeftTopCornerRadius");
      rightTop = property(resolved.config, "RightTopCornerRadius");
      leftBottom = property(resolved.config, "LeftBottomCornerRadius");
      rightBottom = property(resolved.config, "RightBottomCornerRadius");
      strokeRadius = property(resolved.stroke, "Radius");
      // Resolve this before making any changes, so unsupported proxies fail closed.
      method(resolved.proxy, "applyBlurConfig", false);
      gradient = resolved.fallback == null ? null : new GradientShape(resolved.fallback);
    }

    boolean matches(Resolved resolved) {
      return wrapper.get() == resolved.wrapper && fallback.get() == resolved.fallback
          && proxy.get() == resolved.proxy && config.get() == resolved.config && stroke.get() == resolved.stroke;
    }

    boolean apply(float top, float bottom) throws ReflectiveOperationException {
      boolean changed = leftTop.apply(top) | rightTop.apply(top)
          | leftBottom.apply(bottom) | rightBottom.apply(bottom) | strokeRadius.apply(top);
      if (changed) applyConfig();
      return (gradient != null && gradient.apply(top, bottom)) || changed;
    }

    boolean restore() {
      boolean changed = leftTop.restore() | rightTop.restore()
          | leftBottom.restore() | rightBottom.restore() | strokeRadius.restore();
      if (changed) {
        try { applyConfig(); }
        catch (ReflectiveOperationException | RuntimeException unavailable) { /* Best-effort native restoration. */ }
      }
      return (gradient != null && gradient.restore()) || changed;
    }

    private void applyConfig() throws ReflectiveOperationException {
      Object currentProxy = proxy.get(), currentConfig = config.get();
      // A proxy can replace its config. Restoring its old object must not apply a new one.
      if (currentProxy != null && currentConfig != null && call(currentProxy, "getBlurConfig") == currentConfig)
        call(currentProxy, "applyBlurConfig");
    }
  }

  /** Captures native changes observed between our writes; release never overwrites newer values. */
  private static final class FloatProperty {
    final WeakReference<Object> receiver;
    final Method getter, setter;
    float original, written;
    boolean owned;

    FloatProperty(Object object, Method getter, Method setter) {
      receiver = new WeakReference<>(object);
      this.getter = getter;
      this.setter = setter;
    }

    boolean apply(float value) throws ReflectiveOperationException {
      Object object = receiver.get();
      if (object == null) return false;
      float current = ((Number)getter.invoke(object)).floatValue();
      if (!owned || !same(current, written)) original = current;
      written = value;
      owned = true;
      if (!same(current, value)) setter.invoke(object, value);
      return !same(current, value);
    }

    boolean restore() {
      if (!owned) return false;
      owned = false;
      Object object = receiver.get();
      if (object == null) return false;
      try {
        float current = ((Number)getter.invoke(object)).floatValue();
        if (!same(current, written) || same(current, original)) return false;
        setter.invoke(object, original);
        return true;
      } catch (ReflectiveOperationException | RuntimeException unavailable) {
        return false;
      }
    }
  }

  private static final class GradientShape {
    final WeakReference<GradientDrawable> drawable;
    float[] original, written;
    float originalRadius;
    boolean owned;

    GradientShape(GradientDrawable drawable) { this.drawable = new WeakReference<>(drawable); }

    void nativeSource(float ownedTop, float ownedBottom, float nativeTop, float nativeBottom) {
      GradientDrawable shape = drawable.get();
      if (owned || shape == null) return;
      float[] current = shape.getCornerRadii();
      if (!hasRadii(current, ownedTop, ownedBottom)) return;
      original = new float[]{nativeTop, nativeTop, nativeTop, nativeTop,
          nativeBottom, nativeBottom, nativeBottom, nativeBottom};
      written = current.clone();
      owned = true;
    }

    boolean apply(float top, float bottom) {
      GradientDrawable shape = drawable.get();
      if (shape == null) return false;
      float[] current = shape.getCornerRadii();
      if (!owned || !Arrays.equals(current, written)) {
        original = current == null ? null : current.clone();
        originalRadius = shape.getCornerRadius();
      }
      if (hasRadii(current, top, bottom)) {
        written = current;
        owned = true;
        return false;
      }
      written = new float[]{top, top, top, top, bottom, bottom, bottom, bottom};
      owned = true;
      shape.setCornerRadii(written);
      return true;
    }

    boolean restore() {
      if (!owned) return false;
      owned = false;
      GradientDrawable shape = drawable.get();
      if (shape == null || !Arrays.equals(shape.getCornerRadii(), written)) return false;
      if (original == null) shape.setCornerRadius(originalRadius);
      else if (!Arrays.equals(original, written)) shape.setCornerRadii(original);
      else return false;
      return true;
    }
  }

  private static boolean same(float first, float second) {
    return Float.floatToIntBits(first) == Float.floatToIntBits(second);
  }

  private static boolean radius(float value) { return Float.isFinite(value) && value >= 0; }

  private static boolean hasRadii(float[] radii, float top, float bottom) {
    return radii != null && radii.length == 8 && radii[0] == top && radii[1] == top
        && radii[2] == top && radii[3] == top && radii[4] == bottom && radii[5] == bottom
        && radii[6] == bottom && radii[7] == bottom;
  }
}
