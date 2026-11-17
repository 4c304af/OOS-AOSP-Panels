package dev.oos.nativepanels;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/** Restores blur geometry when the native radius setter skips zero or subpixel updates. */
final class NotificationNativeBlurSnapshot {
  private static final Map<Class<?>, Map<String, Method>> METHODS = new HashMap<>();
  private static final String[] CORNERS = {
      "LeftTopCornerRadius", "RightTopCornerRadius", "LeftBottomCornerRadius", "RightBottomCornerRadius", "Radius"};
  private final WeakReference<Object> proxy, config, stroke;
  private final Method getConfig, getStroke, applyConfig;
  private final Method[] getters = new Method[5], setters = new Method[5];
  private final float[] original = new float[5], written = new float[5], current = new float[5];
  private boolean owned;

  /** UI thread only. Capture performs no setters, invalidation, or blur application. */
  static NotificationNativeBlurSnapshot capture(Object proxy) {
    try { return proxy == null ? null : new NotificationNativeBlurSnapshot(proxy); }
    catch (ReflectiveOperationException | RuntimeException unsupported) { return null; }
  }

  private NotificationNativeBlurSnapshot(Object proxy) throws ReflectiveOperationException {
    this.proxy = new WeakReference<>(proxy);
    getConfig = method(proxy, "getBlurConfig", false);
    applyConfig = method(proxy, "applyBlurConfig", false);
    Object config = getConfig.invoke(proxy);
    getStroke = method(config, "getGradientStrokeCornerParam", false);
    Object stroke = getStroke.invoke(config);
    this.config = new WeakReference<>(config);
    this.stroke = new WeakReference<>(stroke);
    for (int i = 0; i < CORNERS.length; i++) {
      Object receiver = i == 4 ? stroke : config;
      getters[i] = method(receiver, "get" + CORNERS[i], false);
      setters[i] = method(receiver, "set" + CORNERS[i], true);
      original[i] = ((Number)getters[i].invoke(receiver)).floatValue();
    }
  }

  boolean matches(Object candidate) {
    if (candidate == null || proxy.get() != candidate) return false;
    Object currentConfig = config.get(), currentStroke = stroke.get();
    try {
      return currentConfig != null && currentStroke != null
          && getConfig.invoke(candidate) == currentConfig && getStroke.invoke(currentConfig) == currentStroke;
    } catch (ReflectiveOperationException | RuntimeException unavailable) { return false; }
  }

  /** Retain newer native writes before our next write, without treating our unchanged fields as native. */
  boolean beforeWrite() {
    float[] current = read();
    if (current == null) return false;
    for (int i = 0; i < current.length; i++) {
      if (!owned || !same(current[i], written[i])) original[i] = current[i];
    }
    return true;
  }

  /** Record what the OEM actually wrote, including its zero/0.5px early-return behavior. */
  boolean written() {
    float[] current = read();
    if (current == null) return false;
    System.arraycopy(current, 0, written, 0, current.length);
    owned = true;
    return true;
  }

  /** Called only for a real incoming native radius request, never our replacement request. */
  void nativeRadius(float top, float bottom) {
    if (!Float.isFinite(top) || !Float.isFinite(bottom) || (top == 0 && bottom == 0)) return;
    original[0] = original[1] = original[4] = top;
    original[2] = original[3] = bottom;
  }

  /** A changed native config or any newer write makes this snapshot ineligible for restoration. */
  boolean restore() {
    if (!owned) return false;
    owned = false;
    float[] current = read();
    if (current == null) return false;
    for (int i = 0; i < current.length; i++) if (!same(current[i], written[i])) return false;
    Object currentConfig = config.get(), currentStroke = stroke.get();
    boolean changed = false;
    for (int i = 0; i < current.length; i++) {
      if (same(current[i], original[i])) continue;
      try {
        // Set before invocation so a setter that throws after writing still gets applied once.
        changed = true;
        setters[i].invoke(i == 4 ? currentStroke : currentConfig, original[i]);
      } catch (ReflectiveOperationException | RuntimeException unavailable) { /* Best effort on vendor variations. */ }
    }
    Object currentProxy = proxy.get();
    if (changed && matches(currentProxy)) {
      try { applyConfig.invoke(currentProxy); }
      catch (ReflectiveOperationException | RuntimeException unavailable) { /* Never interrupt native rendering. */ }
    }
    return changed;
  }

  private float[] read() {
    if (!matches(proxy.get())) return null;
    Object currentConfig = config.get(), currentStroke = stroke.get();
    try {
      for (int i = 0; i < current.length; i++)
        current[i] = ((Number)getters[i].invoke(i == 4 ? currentStroke : currentConfig)).floatValue();
      return current;
    } catch (ReflectiveOperationException | RuntimeException unavailable) { return null; }
  }

  private static Method method(Object receiver, String name, boolean setter) throws ReflectiveOperationException {
    if (receiver == null) throw new IllegalStateException("missing " + name);
    Map<String, Method> cache = METHODS.computeIfAbsent(receiver.getClass(), ignored -> new HashMap<>());
    String key = setter ? name + "(float)" : name;
    Method method = cache.get(key);
    if (method == null) {
      method = receiver.getClass().getMethod(name, setter ? new Class<?>[]{float.class} : new Class<?>[0]);
      cache.put(key, method);
    }
    return method;
  }

  private static boolean same(float first, float second) {
    return Float.floatToIntBits(first) == Float.floatToIntBits(second);
  }
}
