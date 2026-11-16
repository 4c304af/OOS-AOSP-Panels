package dev.oos.nativepanels;

import android.content.Context;
import android.view.LayoutInflater;

/** Limits the resource substitution to the existing grant view's inflation call. */
final class PermissionDialogInflation {
  private record Window(Context source, Context presentation) {}
  private final ThreadLocal<Window> current = new ThreadLocal<>();

  interface Action { Object run() throws Throwable; }

  Object run(Context source, Context presentation, Action action) throws Throwable {
    Window previous = current.get();
    current.set(new Window(source, presentation));
    try { return action.run(); }
    finally {
      if (previous == null) current.remove();
      else current.set(previous);
    }
  }

  LayoutInflater select(Context source, LayoutInflater original) {
    Window window = current.get();
    return window != null && window.source() == source
        ? original.cloneInContext(window.presentation()) : original;
  }
}
