package dev.oos.nativepanels;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Reserves the automatic-brightness button's space on the container-to-track call. */
final class BrightnessTrackPadding {
  private final Object base, padding, matchesPadding;
  private final Method any, then;

  BrightnessTrackPadding(Class<?> modifier, Class<?> function, Object base, Object padding)
      throws ReflectiveOperationException {
    this.base = base;
    this.padding = padding;
    any = modifier.getMethod("any", function);
    then = modifier.getMethod("then", modifier);
    matchesPadding = Proxy.newProxyInstance(function.getClassLoader(), new Class<?>[]{function},
        (proxy, method, args) -> switch (method.getName()) {
          case "invoke" -> args[0] == padding;
          case "equals" -> proxy == args[0];
          case "hashCode" -> System.identityHashCode(proxy);
          case "toString" -> "QS brightness button spacing";
          default -> throw new UnsupportedOperationException(method.getName());
        });
  }

  boolean reserve(Object[] trackArgs) throws ReflectiveOperationException {
    int defaults = (Integer)trackArgs[17];
    Object original = (defaults & (1 << 9)) != 0 ? base : trackArgs[9];
    // The restart lambda passes the previously applied modifier back to BrightnessSlider.
    // Its parent Box already has the button. Preserve both the modifier and the slot table.
    if ((Boolean)any.invoke(original, matchesPadding)) return false;
    trackArgs[9] = then.invoke(padding, original);
    trackArgs[15] = (Integer)trackArgs[15] & ~0x70000000;
    trackArgs[17] = defaults & ~(1 << 9);
    return true;
  }
}
