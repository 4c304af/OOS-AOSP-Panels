package dev.oos.nativepanels;

import android.view.View;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collection;

/** Listen to the icon dispatcher for the display that owns this battery. */
final class StatusBarBatteryTint {
  private final View host;
  private final Object dispatcher, receiver;
  private final Method add, remove, getTint;
  private Collection<?> areas;
  private int tint;

  StatusBarBatteryTint(PanelRuntime r, View host) throws Throwable {
    this.host = host;
    Object displays = r.provider("provideDisplayComponentRepositoryProvider");
    Object display = r.type("com.android.app.displaylib.PerDisplayRepository").getMethod("get", int.class)
        .invoke(displays, host.getDisplay().getDisplayId());
    dispatcher = r.type("com.android.systemui.display.dagger.SystemUIDisplaySubcomponent")
        .getMethod("getDarkIconDispatcher").invoke(display);
    Class<?> api = r.type("com.android.systemui.plugins.DarkIconDispatcher");
    Class<?> callback = r.type(api.getName() + "$DarkReceiver");
    getTint = api.getMethod("getTint", Collection.class, View.class, int.class);
    add = api.getMethod("addDarkReceiver", callback);
    remove = api.getMethod("removeDarkReceiver", callback);
    receiver = Proxy.newProxyInstance(r.loader, new Class<?>[]{callback}, (proxy, m, args) -> {
      switch (m.getName()) {
        case "onDarkChanged" -> update(args[0], (int)args[2]);
        case "onDarkChangedWithContrast" -> update(args[0], (int)args[1]);
        case "equals" -> { return proxy == args[0]; }
        case "hashCode" -> { return System.identityHashCode(proxy); }
        case "toString" -> { return "AOSP status bar battery tint"; }
      }
      return null;
    });
  }

  private void update(Object regions, int color) {
    areas = (Collection<?>) regions;
    tint = color;
    host.invalidate();
  }

  void attach() throws ReflectiveOperationException { add.invoke(dispatcher, receiver); }
  void detach() throws ReflectiveOperationException { remove.invoke(dispatcher, receiver); }

  int color(View slot, int fallback) throws ReflectiveOperationException {
    if (areas == null) return fallback;
    if (areas.isEmpty()) return tint;
    return (int)getTint.invoke(null, areas, slot, tint);
  }
}
