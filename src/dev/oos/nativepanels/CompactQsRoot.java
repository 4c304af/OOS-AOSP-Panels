package dev.oos.nativepanels;

import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;

/** Keeps the expanded scene's root full height while its scroller wraps its content. */
final class CompactQsRoot {
  private final ThreadLocal<Boolean> pending = new ThreadLocal<>();

  Boolean enter(boolean ownedExpandedRoot) {
    Boolean previous = pending.get();
    pending.set(ownedExpandedRoot);
    return previous;
  }

  void leave(Boolean previous) {
    if (previous == null) pending.remove();
    else pending.set(previous);
  }

  XposedInterface.Hooker heightHook(Method fillHeight, Runnable applied) {
    return chain -> {
      Object modifier = chain.proceed();
      if (!Boolean.TRUE.equals(pending.get())) return modifier;
      // The outer Column makes the first semantic-action modifier in this scope.
      // Consume it before any nested composition can produce its own modifier.
      pending.set(false);
      Object result = fillHeight.invoke(null, modifier, 1f);
      applied.run();
      return result;
    };
  }
}
